package com.transactionguard.processing;

import com.transactionguard.domain.AccountBalance;
import com.transactionguard.domain.BusinessRuleViolationException;
import com.transactionguard.domain.LedgerEntry;
import com.transactionguard.domain.TransactionLifecycle;
import com.transactionguard.domain.TransactionRecord;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransactionType;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.observability.ProcessingMetrics;
import com.transactionguard.observability.SensitiveDataMasker;
import com.transactionguard.persistence.AccountBalanceRepository;
import com.transactionguard.persistence.LedgerEntryRepository;
import com.transactionguard.persistence.TransactionRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Applies a single transaction exactly once.
 *
 * <p>The processing transaction follows a strict order:</p>
 * <ol>
 *   <li>atomically claim the record (only one worker can win),</li>
 *   <li>validate terminal business rules against current state,</li>
 *   <li>call the idempotent settlement gateway (may fail transiently),</li>
 *   <li>book the ledger entry and balance in the same DB transaction,</li>
 *   <li>advance the sequence gate so the next record can run.</li>
 * </ol>
 *
 * <p>If the gateway succeeds but the DB commit fails, the retry calls the gateway
 * again with the same effect key; the gateway suppresses the duplicate effect and the
 * local transaction is applied once.</p>
 */
@Service
public class TransactionProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransactionProcessor.class);
    private static final Set<TransactionStatus> CLAIMABLE =
            EnumSet.of(TransactionStatus.RECEIVED, TransactionStatus.RETRY_PENDING);

    private final TransactionRecordRepository transactions;
    private final AccountBalanceRepository balances;
    private final LedgerEntryRepository ledger;
    private final SettlementGateway settlementGateway;
    private final RetryPolicy retryPolicy;
    private final SequenceGate sequenceGate;
    private final TransactionTransitionRecorder recorder;
    private final ProcessingMetrics metrics;
    private final SensitiveDataMasker masker;
    private final Clock clock;

    public TransactionProcessor(TransactionRecordRepository transactions,
                                AccountBalanceRepository balances,
                                LedgerEntryRepository ledger,
                                SettlementGateway settlementGateway,
                                RetryPolicy retryPolicy,
                                SequenceGate sequenceGate,
                                TransactionTransitionRecorder recorder,
                                ProcessingMetrics metrics,
                                SensitiveDataMasker masker,
                                Clock clock) {
        this.transactions = transactions;
        this.balances = balances;
        this.ledger = ledger;
        this.settlementGateway = settlementGateway;
        this.retryPolicy = retryPolicy;
        this.sequenceGate = sequenceGate;
        this.recorder = recorder;
        this.metrics = metrics;
        this.masker = masker;
        this.clock = clock;
    }

    @Transactional
    public ProcessingOutcome process(Long recordId) {
        Instant now = clock.instant();
        int claimed = transactions.claim(recordId, TransactionStatus.PROCESSING, CLAIMABLE, now);
        if (claimed == 0) {
            metrics.claimSkipped();
            return ProcessingOutcome.skipped(recordId);
        }

        TransactionRecord record = transactions.findById(recordId)
                .orElseThrow(() -> new IllegalStateException("Claimed record disappeared: " + recordId));
        TransactionStatus previous = record.getAttemptCount() <= 1
                ? TransactionStatus.RECEIVED : TransactionStatus.RETRY_PENDING;
        recorder.record(record, previous, TransactionStatus.PROCESSING,
                TransitionReason.CLAIMED, "Attempt " + record.getAttemptCount());

        try {
            applyEffect(record, now);
            metrics.transactionProcessed();
            recorder.record(record, TransactionStatus.PROCESSING, TransactionStatus.PROCESSED,
                    TransitionReason.PROCESSED, "Effect booked and sequence advanced");
            log.info("Processed transactionId={} account={} amount={} {} attempt={}",
                    record.getTransactionId(), masker.maskAccountId(record.getAccountId()),
                    record.getAmount(), record.getCurrency(), record.getAttemptCount());
            sequenceGate.advanceAndRelease(record.getStreamId());
            return new ProcessingOutcome(record.getId(), record.getTransactionId(),
                    TransactionStatus.PROCESSED, true, "Processed");
        } catch (TransientSettlementException ex) {
            return handleTransientFailure(record, ex, now);
        } catch (BusinessRuleViolationException ex) {
            return handleBusinessFailure(record, ex, now);
        }
    }

    private void applyEffect(TransactionRecord record, Instant now) {
        if (record.isEffectApplied()) {
            // Defensive: claim guarantees a single writer, this is the last invariant check.
            log.warn("Transaction {} already had its effect applied; skipping", record.getTransactionId());
            return;
        }

        BigDecimal signedAmount = resolveSignedAmount(record);

        AccountBalance balance = balances.findForUpdate(record.getAccountId())
                .orElseGet(() -> new AccountBalance(record.getAccountId(), record.getCurrency(), now));

        if (!balance.getCurrency().equals(record.getCurrency())) {
            throw new BusinessRuleViolationException("CURRENCY_MISMATCH",
                    "Account is held in " + balance.getCurrency() + " but transaction is in " + record.getCurrency());
        }
        if (balance.wouldGoNegative(signedAmount)) {
            throw new BusinessRuleViolationException("INSUFFICIENT_FUNDS",
                    "Insufficient funds for debit of " + record.getAmount() + " " + record.getCurrency());
        }

        // External side effect first: it is idempotent by effect key, so a DB failure
        // afterwards leads to a safe retry rather than a double settlement.
        settlementGateway.settle(new SettlementInstruction(record.getTransactionId(), record.getRequestId(),
                record.getAccountId(), record.getCurrency(), signedAmount));

        BigDecimal newBalance = balance.apply(signedAmount, now);
        balances.save(balance);
        ledger.save(new LedgerEntry(record.getTransactionId(), record.getRequestId(), record.getAccountId(),
                signedAmount, record.getCurrency(), newBalance, now));

        record.markProcessed(now);
        transactions.save(record);
    }

    private BigDecimal resolveSignedAmount(TransactionRecord record) {
        if (record.getTransactionType() == TransactionType.CREDIT) {
            return record.getAmount();
        }
        if (record.getTransactionType() == TransactionType.DEBIT) {
            return record.getAmount().negate();
        }

        String originalId = record.getOriginalTransactionId();
        TransactionRecord original = transactions.findByTransactionId(originalId)
                .orElseThrow(() -> new BusinessRuleViolationException("REVERSAL_ORIGINAL_NOT_FOUND",
                        "Original transaction " + originalId + " was not found"));
        if (original.getStatus() != TransactionStatus.PROCESSED || !original.isEffectApplied()) {
            throw new BusinessRuleViolationException("REVERSAL_ORIGINAL_NOT_PROCESSED",
                    "Original transaction " + originalId + " is not processed");
        }
        if (original.getTransactionType() == TransactionType.REVERSAL) {
            throw new BusinessRuleViolationException("REVERSAL_OF_REVERSAL_NOT_ALLOWED",
                    "Transaction " + originalId + " is itself a reversal");
        }
        LedgerEntry originalEntry = ledger.findByTransactionId(originalId)
                .orElseThrow(() -> new BusinessRuleViolationException("REVERSAL_ORIGINAL_NOT_FOUND",
                        "Ledger entry for original transaction " + originalId + " was not found"));
        if (!originalEntry.getCurrency().equals(record.getCurrency())) {
            throw new BusinessRuleViolationException("CURRENCY_MISMATCH",
                    "Reversal currency " + record.getCurrency() + " does not match original "
                            + originalEntry.getCurrency());
        }
        if (record.getAmount().compareTo(originalEntry.getSignedAmount().abs()) != 0) {
            throw new BusinessRuleViolationException("REVERSAL_AMOUNT_MISMATCH",
                    "Reversal amount " + record.getAmount() + " does not match original amount "
                            + originalEntry.getSignedAmount().abs());
        }
        return originalEntry.getSignedAmount().negate();
    }

    private ProcessingOutcome handleTransientFailure(TransactionRecord record, TransientSettlementException ex,
                                                     Instant now) {
        if (retryPolicy.canRetry(record.getAttemptCount())) {
            Instant nextAttemptAt = retryPolicy.nextAttemptAt(record.getAttemptCount(), now);
            record.markRetryPending(now, nextAttemptAt, ex.code(), ex.getMessage());
            transactions.save(record);
            recorder.record(record, TransactionStatus.PROCESSING, TransactionStatus.RETRY_PENDING,
                    TransitionReason.RETRY_SCHEDULED,
                    "Attempt " + record.getAttemptCount() + " of " + retryPolicy.maxAttempts()
                            + " failed; next attempt at " + nextAttemptAt);
            metrics.retryScheduled();
            log.warn("Transient failure transactionId={} attempt={}/{} code={} nextAttemptAt={}",
                    record.getTransactionId(), record.getAttemptCount(), retryPolicy.maxAttempts(),
                    ex.code(), nextAttemptAt);
            return new ProcessingOutcome(record.getId(), record.getTransactionId(),
                    TransactionStatus.RETRY_PENDING, true, "Retry scheduled");
        }

        record.markFailed(now, ex.code(), "Retry budget exhausted: " + ex.getMessage());
        transactions.save(record);
        recorder.record(record, TransactionStatus.PROCESSING, TransactionStatus.FAILED,
                TransitionReason.RETRY_EXHAUSTED,
                "All " + retryPolicy.maxAttempts() + " attempts failed: " + ex.getMessage());
        metrics.transactionFailed();
        log.error("Retry budget exhausted transactionId={} attempts={} code={}",
                record.getTransactionId(), record.getAttemptCount(), ex.code());
        sequenceGate.advanceAndRelease(record.getStreamId());
        return new ProcessingOutcome(record.getId(), record.getTransactionId(),
                TransactionStatus.FAILED, true, "Retry budget exhausted");
    }

    private ProcessingOutcome handleBusinessFailure(TransactionRecord record, BusinessRuleViolationException ex,
                                                    Instant now) {
        record.markRejected(now, ex.code(), ex.getMessage());
        transactions.save(record);
        recorder.record(record, TransactionStatus.PROCESSING, TransactionStatus.REJECTED,
                TransitionReason.BUSINESS_RULE_VIOLATION, ex.code() + ": " + ex.getMessage());
        metrics.transactionRejected();
        log.info("Business rejection transactionId={} code={} detail={}",
                record.getTransactionId(), ex.code(), ex.getMessage());
        sequenceGate.advanceAndRelease(record.getStreamId());
        return new ProcessingOutcome(record.getId(), record.getTransactionId(),
                TransactionStatus.REJECTED, true, ex.code());
    }

    /**
     * Exposed for tests that assert the lifecycle helper is used consistently.
     */
    public static boolean isValidTransition(TransactionStatus from, TransactionStatus to) {
        return TransactionLifecycle.isAllowed(from, to);
    }

    Optional<TransactionRecord> find(Long recordId) {
        return transactions.findById(recordId);
    }
}
