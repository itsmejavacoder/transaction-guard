package com.transactionguard.processing;

import java.time.Instant;

/**
 * Outcome of one worker cycle, returned by the manual trigger endpoint as well as
 * logged by the scheduled worker.
 */
public record ProcessingCycleReport(Instant startedAt,
                                    Instant finishedAt,
                                    boolean alreadyRunning,
                                    int claimedCount,
                                    int processedCount,
                                    int retryScheduledCount,
                                    int rejectedCount,
                                    int failedCount,
                                    int skippedCount) {

    public static ProcessingCycleReport busy(Instant now) {
        return new ProcessingCycleReport(now, now, true, 0, 0, 0, 0, 0, 0);
    }
}
