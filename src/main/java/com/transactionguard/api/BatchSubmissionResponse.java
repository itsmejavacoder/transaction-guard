package com.transactionguard.api;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.ingestion.SubmissionOutcome;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Response of a batch submission: the per-record outcomes plus a quick tally so the
 * caller can alert on rejects without re-counting.
 */
public record BatchSubmissionResponse(int total,
                                     Map<TransactionStatus, Integer> countsByStatus,
                                     List<SubmissionOutcome> results) {

    public static BatchSubmissionResponse of(List<SubmissionOutcome> outcomes) {
        Map<TransactionStatus, Integer> counts = new EnumMap<>(TransactionStatus.class);
        for (SubmissionOutcome outcome : outcomes) {
            counts.merge(outcome.status(), 1, Integer::sum);
        }
        return new BatchSubmissionResponse(outcomes.size(), Map.copyOf(counts), List.copyOf(outcomes));
    }
}
