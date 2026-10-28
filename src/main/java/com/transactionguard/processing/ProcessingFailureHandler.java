package com.transactionguard.processing;

import com.transactionguard.domain.TransactionRecord;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.observability.ProcessingMetrics;
import com.transactionguard.persistence.TransactionRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Isolates a poison record. If processing fails for an unexpected reason (bug, DB
 * constraint, serialization problem), the record is marked FAILED in a fresh
 * transaction instead of being retried forever or aborting the whole cycle.
 */
@Component
public class ProcessingFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(ProcessingFailureHandler.class);

    private final TransactionRecordRepository transactions;
    private final TransactionTransitionRecorder recorder;
    private final SequenceGate sequenceGate;
    private final ProcessingMetrics metrics;
    private final Clock clock;

    public ProcessingFailureHandler(TransactionRecordRepository transactions,
                                    TransactionTransitionRecorder recorder,
                                    SequenceGate sequenceGate,
                                    ProcessingMetrics metrics,
                                    Clock clock) {
        this.transactions = transactions;
        this.recorder = recorder;
        this.sequenceGate = sequenceGate;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnexpectedFailure(Long recordId, Throwable cause) {
        Instant now = clock.instant();
        transactions.findById(recordId).ifPresent(record -> {
            if (record.getStatus().isTerminal()) {
                return;
            }
            TransactionStatus from = record.getStatus();
            record.markFailed(now, "UNEXPECTED_PROCESSING_ERROR",
                    cause == null ? "Unexpected processing error" : cause.getMessage());
            transactions.save(record);
            recorder.record(record, from, TransactionStatus.FAILED,
                    TransitionReason.RETRY_EXHAUSTED,
                    "Unexpected processing error: "
                            + (cause == null ? "unknown" : cause.getClass().getSimpleName()));
            metrics.transactionFailed();
            log.error("Marked transaction {} as FAILED after unexpected error",
                    record.getTransactionId(), cause);
            sequenceGate.advanceAndRelease(record.getStreamId());
        });
    }
}
