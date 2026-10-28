package com.transactionguard.domain;

/**
 * Raised when an operator asks to requeue a record that is not in a recoverable
 * terminal state. Only {@code FAILED} records may be requeued; everything else is
 * either still in flight or already has a final business outcome.
 */
public class RequeueNotAllowedException extends RuntimeException {

    public RequeueNotAllowedException(String transactionId, TransactionStatus status) {
        super("Transaction " + transactionId + " cannot be requeued from status " + status
                + "; only FAILED records are requeueable");
    }
}
