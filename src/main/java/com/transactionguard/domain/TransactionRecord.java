package com.transactionguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persisted state of one business transaction. One row per {@code transactionId},
 * which is the storage-level guarantee behind business idempotency.
 *
 * <p>Payload columns are nullable because an invalid feed row is still persisted as
 * a REJECTED record (for auditability and to consume its sequence); identity columns
 * are mandatory.</p>
 */
@Entity
@Table(
        name = "transaction_record",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_transaction_record_request", columnNames = "request_id"),
                @UniqueConstraint(name = "uk_transaction_record_transaction", columnNames = "transaction_id"),
                @UniqueConstraint(name = "uk_transaction_record_stream_sequence",
                        columnNames = {"stream_id", "sequence_number"})
        },
        indexes = {
                @Index(name = "idx_transaction_record_status", columnList = "status"),
                @Index(name = "idx_transaction_record_account", columnList = "account_id")
        })
public class TransactionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, updatable = false, length = 64)
    private String requestId;

    @Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    private String transactionId;

    @Column(name = "account_id", nullable = false, updatable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", length = 16)
    private TransactionType transactionType;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private long sequenceNumber;

    @Column(name = "event_time")
    private Instant eventTime;

    @Column(name = "original_transaction_id", length = 64)
    private String originalTransactionId;

    @Column(name = "stream_id", nullable = false, updatable = false, length = 64)
    private String streamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransactionStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 512)
    private String failureMessage;

    @Column(name = "effect_applied", nullable = false)
    private boolean effectApplied;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected TransactionRecord() {
        // for JPA
    }

    private TransactionRecord(TransactionRequest request, String streamId, long sequenceNumber,
                              TransactionStatus status, Instant now) {
        this.requestId = request.requestId();
        this.transactionId = request.transactionId();
        this.accountId = request.accountId();
        this.transactionType = request.transactionType();
        this.amount = request.amount() == null ? null : request.amount().setScale(2);
        this.currency = request.currency();
        this.sequenceNumber = sequenceNumber;
        this.eventTime = request.eventTime();
        this.originalTransactionId = request.originalTransactionId();
        this.streamId = streamId;
        this.status = status;
        this.receivedAt = now;
        this.updatedAt = now;
    }

    public static TransactionRecord admitted(TransactionRequest request, String streamId,
                                             long sequenceNumber, TransactionStatus initialStatus, Instant now) {
        return new TransactionRecord(request, streamId, sequenceNumber, initialStatus, now);
    }

    /**
     * Builds a rejected record for a structurally invalid feed row. Keeping the row
     * makes the rejection auditable and, crucially, lets the sequence gate move past
     * a permanently bad sequence instead of blocking the whole stream.
     */
    public static TransactionRecord rejected(TransactionRequest request, String streamId, long sequenceNumber,
                                             String code, String message, Instant now) {
        TransactionRecord record = new TransactionRecord(request, streamId, sequenceNumber, TransactionStatus.REJECTED, now);
        record.failureCode = code;
        record.failureMessage = truncate(message);
        return record;
    }

    public void markProcessing(Instant now) {
        TransactionLifecycle.require(status, TransactionStatus.PROCESSING);
        this.status = TransactionStatus.PROCESSING;
        this.updatedAt = now;
    }

    public void markPendingPredecessor(Instant now) {
        TransactionLifecycle.require(status, TransactionStatus.PENDING_PREDECESSOR);
        this.status = TransactionStatus.PENDING_PREDECESSOR;
        this.updatedAt = now;
    }

    public void releaseForProcessing(Instant now) {
        TransactionLifecycle.require(status, TransactionStatus.RECEIVED);
        this.status = TransactionStatus.RECEIVED;
        this.updatedAt = now;
    }

    public void markProcessed(Instant now) {
        TransactionLifecycle.require(status, TransactionStatus.PROCESSED);
        this.status = TransactionStatus.PROCESSED;
        this.effectApplied = true;
        this.failureCode = null;
        this.failureMessage = null;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    public void markRetryPending(Instant now, Instant nextAttemptAt, String code, String message) {
        TransactionLifecycle.require(status, TransactionStatus.RETRY_PENDING);
        this.status = TransactionStatus.RETRY_PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.failureCode = code;
        this.failureMessage = truncate(message);
        this.updatedAt = now;
    }

    public void markRejected(Instant now, String code, String message) {
        TransactionLifecycle.require(status, TransactionStatus.REJECTED);
        this.status = TransactionStatus.REJECTED;
        this.failureCode = code;
        this.failureMessage = truncate(message);
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    public void markFailed(Instant now, String code, String message) {
        TransactionLifecycle.require(status, TransactionStatus.FAILED);
        this.status = TransactionStatus.FAILED;
        this.failureCode = code;
        this.failureMessage = truncate(message);
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    /**
     * Operator-driven recovery of a {@code FAILED} record. The retry budget is reset
     * because the operator is asserting that the cause has been fixed; the attempt is
     * still visible as its own audit transition. The sequence is intentionally not
     * re-reserved: a failed record already consumed its slot, so a requeued record is
     * processed out of stream order (documented trade-off).
     */
    public void markRequeued(Instant now) {
        TransactionLifecycle.require(status, TransactionStatus.RETRY_PENDING);
        this.status = TransactionStatus.RETRY_PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.failureCode = null;
        this.failureMessage = null;
        this.updatedAt = now;
    }

    /**
     * Returns a claim whose owner disappeared mid-cycle to the worker. The attempt
     * counter is kept: a record that repeatedly kills its worker must still exhaust
     * its retry budget instead of being resurrected forever.
     */
    public void markStaleClaimRecovered(Instant now) {
        TransactionLifecycle.require(status, TransactionStatus.RETRY_PENDING);
        this.status = TransactionStatus.RETRY_PENDING;
        this.nextAttemptAt = now;
        this.failureCode = null;
        this.failureMessage = null;
        this.updatedAt = now;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getAccountId() {
        return accountId;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public String getOriginalTransactionId() {
        return originalTransactionId;
    }

    public String getStreamId() {
        return streamId;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public boolean isEffectApplied() {
        return effectApplied;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
