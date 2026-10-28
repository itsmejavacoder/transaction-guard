package com.transactionguard.domain;

/**
 * Stable reason codes written to the audit trail and exposed in reports. Keeping
 * them in one place avoids stringly-typed drift between processor, API and tests.
 */
public final class TransitionReason {

    public static final String INGESTED_READY = "INGESTED_READY";
    public static final String INGESTED_PENDING = "INGESTED_PENDING_PREDECESSOR";
    public static final String INGESTED_REJECTED = "INGESTED_REJECTED";
    public static final String DUPLICATE_REQUEST = "DUPLICATE_REQUEST";
    public static final String DUPLICATE_TRANSACTION = "DUPLICATE_TRANSACTION";
    public static final String SEQUENCE_CONFLICT = "SEQUENCE_CONFLICT";
    public static final String STALE_SEQUENCE = "STALE_SEQUENCE";
    public static final String RELEASED = "RELEASED_AFTER_PREDECESSOR";
    public static final String CLAIMED = "CLAIMED_BY_WORKER";
    public static final String PROCESSED = "EFFECT_APPLIED";
    public static final String RETRY_SCHEDULED = "RETRY_SCHEDULED";
    public static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    public static final String BUSINESS_RULE_VIOLATION = "BUSINESS_RULE_VIOLATION";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String STREAM_ADVANCED = "STREAM_ADVANCED";

    private TransitionReason() {
    }
}
