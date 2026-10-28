package com.transactionguard.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Inbound transaction request as received from an upstream system.
 *
 * <p>The record is intentionally transport-agnostic: the same shape is used by the
 * REST API and by JSON file ingestion. Validation is performed explicitly by
 * {@code TransactionRequestValidator} so that every rejected feed row can be
 * reported with a stable error code without failing the whole batch.</p>
 *
 * @param requestId            unique idempotency key of this delivery attempt
 * @param transactionId        unique business transaction id
 * @param accountId            account the transaction belongs to
 * @param transactionType      CREDIT, DEBIT or REVERSAL
 * @param amount               strictly positive monetary amount
 * @param currency             ISO-4217 code, three upper-case letters
 * @param sequenceNumber       1-based position in the ordered stream
 * @param eventTime            optional upstream event time, informational
 * @param originalTransactionId required for REVERSAL, must reference a processed transaction
 */
public record TransactionRequest(
        String requestId,
        String transactionId,
        String accountId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        Long sequenceNumber,
        Instant eventTime,
        String originalTransactionId) {

    public long sequence() {
        return sequenceNumber == null ? 0L : sequenceNumber;
    }

    public Instant eventTimeOr(Instant fallback) {
        return eventTime == null ? fallback : eventTime;
    }
}
