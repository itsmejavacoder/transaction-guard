package com.transactionguard.processing;

import com.transactionguard.domain.TransactionStatus;

/**
 * Result of attempting to process one record.
 */
public record ProcessingOutcome(Long recordId,
                                String transactionId,
                                TransactionStatus status,
                                boolean claimed,
                                String message) {

    public static ProcessingOutcome skipped(Long recordId) {
        return new ProcessingOutcome(recordId, null, null, false, "Record was already claimed");
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }
}
