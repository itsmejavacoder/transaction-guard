package com.transactionguard.support;

import com.transactionguard.domain.TransactionRequest;
import com.transactionguard.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Test data builder. Keeps test methods focused on behaviour rather than on
 * seven-argument constructors.
 */
public final class TransactionFixtures {

    private static final Instant EVENT_TIME = Instant.parse("2026-10-27T10:00:00Z");

    private TransactionFixtures() {
    }

    public static TransactionRequest credit(String requestId, String transactionId, String accountId,
                                            String amount, long sequence) {
        return transaction(requestId, transactionId, accountId, TransactionType.CREDIT, amount, "INR", sequence);
    }

    public static TransactionRequest debit(String requestId, String transactionId, String accountId,
                                           String amount, long sequence) {
        return transaction(requestId, transactionId, accountId, TransactionType.DEBIT, amount, "INR", sequence);
    }

    public static TransactionRequest reversal(String requestId, String transactionId, String accountId,
                                              String amount, long sequence, String originalTransactionId) {
        return new TransactionRequest(requestId, transactionId, accountId, TransactionType.REVERSAL,
                new BigDecimal(amount), "INR", sequence, EVENT_TIME, originalTransactionId);
    }

    public static TransactionRequest transaction(String requestId, String transactionId, String accountId,
                                                 TransactionType type, String amount, String currency,
                                                 Long sequence) {
        return new TransactionRequest(requestId, transactionId, accountId, type,
                amount == null ? null : new BigDecimal(amount), currency, sequence, EVENT_TIME, null);
    }
}
