package com.transactionguard.domain;

/**
 * Raised when a caller references a business transaction that has never been seen.
 */
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(String transactionId) {
        super("Transaction not found: " + transactionId);
    }
}
