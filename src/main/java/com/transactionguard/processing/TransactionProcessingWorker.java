package com.transactionguard.processing;

import com.transactionguard.config.TransactionGuardProperties;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.persistence.TransactionRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background worker. One cycle repeatedly claims ready (or due-for-retry) records and
 * hands them to {@link TransactionProcessor} until the stream is exhausted or the
 * per-cycle safety cap is reached.
 *
 * <p>A guard flag prevents overlapping cycles: even if a cycle takes longer than the
 * scheduler interval, the next tick becomes a no-op instead of doubling the load.</p>
 */
@Component
public class TransactionProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(TransactionProcessingWorker.class);
    private static final Set<TransactionStatus> CLAIMABLE =
            EnumSet.of(TransactionStatus.RECEIVED, TransactionStatus.RETRY_PENDING);

    private final TransactionRecordRepository transactions;
    private final TransactionProcessor processor;
    private final ProcessingFailureHandler failureHandler;
    private final TransactionGuardProperties properties;
    private final Clock clock;
    private final AtomicBoolean cycleRunning = new AtomicBoolean(false);

    public TransactionProcessingWorker(TransactionRecordRepository transactions,
                                       TransactionProcessor processor,
                                       ProcessingFailureHandler failureHandler,
                                       TransactionGuardProperties properties,
                                       Clock clock) {
        this.transactions = transactions;
        this.processor = processor;
        this.failureHandler = failureHandler;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${transactionguard.worker.interval-ms:250}")
    public void scheduledCycle() {
        if (!properties.worker().enabled()) {
            return;
        }
        try {
            ProcessingCycleReport report = runCycle();
            if (!report.alreadyRunning() && report.claimedCount() > 0) {
                log.info("Worker cycle finished claimed={} processed={} retry={} rejected={} failed={} in {} ms",
                        report.claimedCount(), report.processedCount(), report.retryScheduledCount(),
                        report.rejectedCount(), report.failedCount(),
                        report.finishedAt().toEpochMilli() - report.startedAt().toEpochMilli());
            }
        } catch (RuntimeException ex) {
            log.error("Worker cycle failed unexpectedly", ex);
        }
    }

    /**
     * Runs one cycle synchronously. Exposed for deterministic demos and tests through
     * {@code POST /api/v1/processing/cycles}.
     */
    public ProcessingCycleReport runCycle() {
        Instant startedAt = clock.instant();
        if (!cycleRunning.compareAndSet(false, true)) {
            return ProcessingCycleReport.busy(startedAt);
        }
        try {
            int claimed = 0;
            int processed = 0;
            int retries = 0;
            int rejected = 0;
            int failed = 0;
            int skipped = 0;
            int batchSize = properties.worker().fetchSize();
            int maxRecords = properties.worker().maxRecordsPerCycle();

            while (claimed < maxRecords) {
                List<Long> claimable = transactions.findClaimableIds(CLAIMABLE, clock.instant(),
                        PageRequest.of(0, batchSize));
                if (claimable.isEmpty()) {
                    break;
                }

                boolean progress = false;
                for (Long id : claimable) {
                    if (claimed >= maxRecords) {
                        break;
                    }
                    ProcessingOutcome outcome;
                    try {
                        outcome = processor.process(id);
                    } catch (RuntimeException ex) {
                        log.error("Unexpected failure while processing recordId={}", id, ex);
                        failureHandler.markUnexpectedFailure(id, ex);
                        failed++;
                        progress = true;
                        continue;
                    }

                    if (!outcome.claimed()) {
                        skipped++;
                        continue;
                    }

                    claimed++;
                    progress = true;
                    switch (outcome.status()) {
                        case PROCESSED -> processed++;
                        case RETRY_PENDING -> retries++;
                        case REJECTED -> rejected++;
                        case FAILED -> failed++;
                        default -> {
                            // no other status can be produced by the processor
                        }
                    }
                }

                if (!progress) {
                    // Everything was claimed by another worker; do not spin.
                    break;
                }
            }

            return new ProcessingCycleReport(startedAt, clock.instant(), false,
                    claimed, processed, retries, rejected, failed, skipped);
        } finally {
            cycleRunning.set(false);
        }
    }
}
