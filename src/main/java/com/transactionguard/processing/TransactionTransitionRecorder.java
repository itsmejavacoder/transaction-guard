package com.transactionguard.processing;

import com.transactionguard.domain.TransactionRecord;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransactionTransition;
import com.transactionguard.persistence.TransactionTransitionRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Writes the append-only audit trail. Every business-relevant step (accepted,
 * deferred, released, claimed, processed, retried, rejected, duplicate) becomes one
 * row, which the API exposes as the transaction history.
 */
@Component
public class TransactionTransitionRecorder {

    private static final int MAX_DETAIL_LENGTH = 500;

    private final TransactionTransitionRepository transitions;
    private final Clock clock;

    public TransactionTransitionRecorder(TransactionTransitionRepository transitions, Clock clock) {
        this.transitions = transitions;
        this.clock = clock;
    }

    public void record(TransactionRecord record, TransactionStatus from, TransactionStatus to,
                       String reason, String detail) {
        record(record.getTransactionId(), record.getRequestId(), from, to, reason, detail, clock.instant());
    }

    public void record(String transactionId, String requestId, TransactionStatus from, TransactionStatus to,
                       String reason, String detail, Instant occurredAt) {
        String safeDetail = detail != null && detail.length() > MAX_DETAIL_LENGTH
                ? detail.substring(0, MAX_DETAIL_LENGTH)
                : detail;
        transitions.save(new TransactionTransition(
                transactionId,
                requestId,
                from == null ? null : from.name(),
                to.name(),
                reason,
                safeDetail,
                occurredAt == null ? clock.instant() : occurredAt));
    }
}
