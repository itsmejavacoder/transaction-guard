package com.transactionguard.processing;

import com.transactionguard.config.TransactionGuardProperties;
import com.transactionguard.domain.TransactionNotFoundException;
import com.transactionguard.domain.TransactionRecord;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.domain.RequeueNotAllowedException;
import com.transactionguard.observability.ProcessingMetrics;
import com.transactionguard.persistence.TransactionRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Operator recovery paths for records that the normal worker cannot move.
 *
 * <p>Two cases exist:</p>
 * <ul>
 *   <li><b>Requeue</b> — a {@code FAILED} record whose cause has been fixed is moved
 *       back to {@code RETRY_PENDING} with a fresh retry budget. This is the explicit
 *       "recoverable" half of the resilience requirement: the automatic pipeline never
 *       resurrects a failed record, an operator does.</li>
 *   <li><b>Stale claim</b> — a {@code PROCESSING} record whose worker died mid-cycle is
 *       returned to {@code RETRY_PENDING} after a visibility timeout. Because the
 *       settlement gateway is idempotent by effect key, retrying is safe even when the
 *       original attempt had already reached the gateway.</li>
 * </ul>
 *
 * <p>A stale claim that has already consumed its retry budget becomes {@code FAILED}:
 * a record that repeatedly kills its worker must not be retried forever.</p>
 */
@Service
public class TransactionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TransactionRecoveryService.class);
    private static final String STALE_CLAIM_EXHAUSTED = "STALE_CLAIM_EXHAUSTED";

    private final TransactionRecordRepository transactions;
    private final TransactionTransitionRecorder recorder;
    private final SequenceGate sequenceGate;
    private final RetryPolicy retryPolicy;
    private final ProcessingMetrics metrics;
    private final TransactionGuardProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public TransactionRecoveryService(TransactionRecordRepository transactions,
                                      TransactionTransitionRecorder recorder,
                                      SequenceGate sequenceGate,
                                      RetryPolicy retryPolicy,
                                      ProcessingMetrics metrics,
                                      TransactionGuardProperties properties,
                                      PlatformTransactionManager transactionManager,
                                      Clock clock) {
        this.transactions = transactions;
        this.recorder = recorder;
        this.sequenceGate = sequenceGate;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Requeues a {@code FAILED} transaction. The retry budget is reset because the
     * operator is asserting that the root cause has been addressed; the requeue itself
     * is audited so the intervention is never invisible.
     *
     * @throws TransactionNotFoundException if the transaction has never been seen
     * @throws RequeueNotAllowedException   if the record is not {@code FAILED}
     */
    @Transactional
    public TransactionRecord requeue(String transactionId) {
        TransactionRecord record = transactions.findByTransactionId(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (record.getStatus() != TransactionStatus.FAILED) {
            throw new RequeueNotAllowedException(transactionId, record.getStatus());
        }

        Instant now = clock.instant();
        record.markRequeued(now);
        transactions.save(record);
        recorder.record(record, TransactionStatus.FAILED, TransactionStatus.RETRY_PENDING,
                TransitionReason.MANUAL_REQUEUE,
                "Operator requeue; retry budget reset to " + retryPolicy.maxAttempts() + " attempt(s)");
        metrics.transactionRequeued();
        log.info("Requeued failed transactionId={} attemptBudget={}",
                record.getTransactionId(), retryPolicy.maxAttempts());
        return record;
    }

    /**
     * Sweeps abandoned {@code PROCESSING} claims. Each record is recovered in its own
     * transaction so one poison record cannot abort the whole sweep.
     */
    public RecoveryReport recoverStaleClaims() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.recovery().staleClaimTimeout());
        List<TransactionRecord> stale = transactions
                .findByStatusAndUpdatedAtBefore(TransactionStatus.PROCESSING, cutoff);

        int recovered = 0;
        int failed = 0;
        for (TransactionRecord candidate : stale) {
            try {
                RecoveryDisposition disposition = transactionTemplate.execute(
                        status -> recoverOne(candidate.getId()));
                if (disposition == RecoveryDisposition.RECOVERED) {
                    recovered++;
                } else if (disposition == RecoveryDisposition.FAILED) {
                    failed++;
                }
            } catch (RuntimeException ex) {
                log.error("Stale-claim recovery failed transactionId={}",
                        candidate.getTransactionId(), ex);
            }
        }

        if (recovered + failed > 0) {
            log.warn("Stale-claim sweep recovered={} failed={} cutoff={}", recovered, failed, cutoff);
        }
        return new RecoveryReport(now, recovered, failed);
    }

    /**
     * Periodic safety net for single-instance and multi-instance deployments. Disabled
     * by {@code transactionguard.recovery.enabled=false}; the manual endpoint and the
     * startup sweep still work when it is off.
     */
    @Scheduled(fixedDelayString = "${transactionguard.recovery.interval-ms:60000}")
    public void scheduledStaleClaimRecovery() {
        if (!properties.recovery().enabled()) {
            return;
        }
        try {
            recoverStaleClaims();
        } catch (RuntimeException ex) {
            log.error("Scheduled stale-claim recovery failed unexpectedly", ex);
        }
    }

    private RecoveryDisposition recoverOne(Long recordId) {
        TransactionRecord record = transactions.findById(recordId).orElse(null);
        if (record == null || record.getStatus() != TransactionStatus.PROCESSING) {
            // Another sweep or an operator already recovered it.
            return RecoveryDisposition.SKIPPED;
        }

        Instant now = clock.instant();
        if (record.getAttemptCount() >= retryPolicy.maxAttempts()) {
            record.markFailed(now, STALE_CLAIM_EXHAUSTED,
                    "Abandoned claim after " + record.getAttemptCount() + " attempt(s)");
            transactions.save(record);
            recorder.record(record, TransactionStatus.PROCESSING, TransactionStatus.FAILED,
                    TransitionReason.RETRY_EXHAUSTED,
                    "Abandoned claim; retry budget of " + retryPolicy.maxAttempts() + " exhausted");
            metrics.transactionFailed();
            sequenceGate.advanceAndRelease(record.getStreamId());
            log.error("Abandoned claim exhausted retry budget transactionId={} attempts={}",
                    record.getTransactionId(), record.getAttemptCount());
            return RecoveryDisposition.FAILED;
        }

        record.markStaleClaimRecovered(now);
        transactions.save(record);
        recorder.record(record, TransactionStatus.PROCESSING, TransactionStatus.RETRY_PENDING,
                TransitionReason.STALE_CLAIM_RECOVERED,
                "Claim older than " + properties.recovery().staleClaimTimeout()
                        + " returned to the worker");
        metrics.staleClaimRecovered();
        log.warn("Recovered abandoned claim transactionId={} attempt={}/{}",
                record.getTransactionId(), record.getAttemptCount(), retryPolicy.maxAttempts());
        return RecoveryDisposition.RECOVERED;
    }

    private enum RecoveryDisposition {
        RECOVERED,
        FAILED,
        SKIPPED
    }
}
