package com.transactionguard.ingestion;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.validation.ValidationIssue;

import java.time.Instant;
import java.util.List;

/**
 * Result of submitting one request. A duplicate is not an error: it reports the
 * original outcome and confirms that no new effect was applied.
 *
 * @param recorded whether the submission produced a persisted transaction record
 */
public record SubmissionOutcome(
        String requestId,
        String transactionId,
        TransactionStatus status,
        boolean duplicate,
        boolean recorded,
        String message,
        List<ValidationIssue> validationIssues,
        Instant occurredAt,
        long sequenceNumber,
        String streamId) {

    public SubmissionOutcome {
        validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
    }
}
