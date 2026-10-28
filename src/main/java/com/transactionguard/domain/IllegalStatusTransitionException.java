package com.transactionguard.domain;

/**
 * Thrown when code attempts a status change that the lifecycle does not permit.
 */
public class IllegalStatusTransitionException extends RuntimeException {

    private final TransactionStatus from;
    private final TransactionStatus to;

    public IllegalStatusTransitionException(TransactionStatus from, TransactionStatus to) {
        super("Illegal transaction status transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public TransactionStatus from() {
        return from;
    }

    public TransactionStatus to() {
        return to;
    }
}
