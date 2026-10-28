package com.transactionguard.processing;

import java.time.Instant;

/**
 * Outcome of one stale-claim recovery sweep.
 *
 * @param generatedAt when the sweep ran
 * @param recovered   number of {@code PROCESSING} records handed back to the worker
 * @param failed      number of records whose retry budget was already exhausted
 */
public record RecoveryReport(Instant generatedAt, int recovered, int failed) {

    public int touched() {
        return recovered + failed;
    }
}
