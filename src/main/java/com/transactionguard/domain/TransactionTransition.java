package com.transactionguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only audit trail of status changes and duplicate attempts. Having this
 * separate from the mutable record makes the processing history explainable long
 * after the fact ("why is this transaction PROCESSED?").
 */
@Entity
@Table(name = "transaction_transition", indexes = {
        @Index(name = "idx_transition_transaction", columnList = "transaction_id"),
        @Index(name = "idx_transition_to_status", columnList = "to_status")
})
public class TransactionTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "from_status", length = 32)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 32)
    private String toStatus;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected TransactionTransition() {
        // for JPA
    }

    public TransactionTransition(String transactionId, String requestId, String fromStatus, String toStatus,
                                 String reason, String detail, Instant occurredAt) {
        this.transactionId = transactionId;
        this.requestId = requestId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
