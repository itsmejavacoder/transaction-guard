package com.transactionguard.api;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Full read model of one transaction: current state, ledger evidence and the complete
 * audit history.
 */
public record TransactionDetail(
        String requestId,
        String transactionId,
        String accountId,
        String streamId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        long sequenceNumber,
        Instant eventTime,
        String originalTransactionId,
        TransactionStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String failureCode,
        String failureMessage,
        boolean effectApplied,
        Instant receivedAt,
        Instant updatedAt,
        LedgerEntryView ledgerEntry,
        List<TransitionView> history) {
}
