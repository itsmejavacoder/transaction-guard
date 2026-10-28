package com.transactionguard.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregate result of ingesting a feed, used by startup ingestion and the batch API.
 */
public record FeedIngestionReport(String source,
                                  Instant startedAt,
                                  Instant finishedAt,
                                  int receivedCount,
                                  int pendingCount,
                                  int duplicateCount,
                                  int rejectedCount,
                                  List<SubmissionOutcome> outcomes) {

    public FeedIngestionReport {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    public static FeedIngestionReport of(String source, Instant startedAt, Instant finishedAt,
                                         List<SubmissionOutcome> outcomes) {
        int received = 0;
        int pending = 0;
        int duplicates = 0;
        int rejected = 0;
        for (SubmissionOutcome outcome : outcomes) {
            switch (outcome.status()) {
                case RECEIVED -> received++;
                case PENDING_PREDECESSOR -> pending++;
                case DUPLICATE -> duplicates++;
                case REJECTED -> rejected++;
                default -> {
                    // other statuses cannot be produced during ingestion
                }
            }
        }
        return new FeedIngestionReport(source, startedAt, finishedAt,
                received, pending, duplicates, rejected, outcomes);
    }

    public int totalCount() {
        return outcomes.size();
    }

    public Map<String, Integer> counts() {
        return Map.of(
                "received", receivedCount,
                "pending", pendingCount,
                "duplicate", duplicateCount,
                "rejected", rejectedCount);
    }
}
