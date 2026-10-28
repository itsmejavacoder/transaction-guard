package com.transactionguard.domain;

/**
 * Business effect of a transaction.
 */
public enum TransactionType {
    /** Money enters the account. */
    CREDIT,
    /** Money leaves the account. */
    DEBIT,
    /** Compensates a previously processed transaction. */
    REVERSAL;

    public boolean isReversal() {
        return this == REVERSAL;
    }
}
