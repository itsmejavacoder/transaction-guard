package com.transactionguard.domain;

/**
 * Transaction lifecycle.
 *
 * <pre>
 *                ┌──────────────────────────► REJECTED      (validation / business rule)
 *                │
 * RECEIVED ──────┼──► PROCESSING ──► PROCESSED              (effect applied exactly once)
 *    ▲           │        │
 *    │           │        └──► RETRY_PENDING ──► PROCESSING ──► FAILED (retry budget exhausted)
 *    │           │                    ▲
 *    │           │                    └── transient settlement failure
 *    │           │
 * PENDING_PREDECESSOR ──(gap filled)──┘
 *                │
 *                └──► DUPLICATE (API outcome for replayed requests; not stored as a record state)
 * </pre>
 */
public enum TransactionStatus {
    /** Accepted and ready to be claimed by the worker. */
    RECEIVED(false),
    /** Waiting for an earlier sequence number of the same stream. */
    PENDING_PREDECESSOR(false),
    /** Claimed by a worker thread. */
    PROCESSING(false),
    /** Transient failure occurred, waiting for the retry window. */
    RETRY_PENDING(false),
    /** Effect applied; terminal. */
    PROCESSED(true),
    /** Request replayed after the business transaction was already seen; terminal outcome. */
    DUPLICATE(true),
    /** Rejected by validation or a terminal business rule; terminal, no effect. */
    REJECTED(true),
    /** Retry budget exhausted; terminal for the automatic pipeline, recoverable by an operator requeue. */
    FAILED(true);

    private final boolean terminal;

    TransactionStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean isClaimable() {
        return this == RECEIVED || this == RETRY_PENDING;
    }
}
