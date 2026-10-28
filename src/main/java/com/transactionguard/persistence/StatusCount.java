package com.transactionguard.persistence;

import com.transactionguard.domain.TransactionStatus;

/**
 * Lightweight projection for the processing summary.
 */
public record StatusCount(TransactionStatus status, long total) {
}
