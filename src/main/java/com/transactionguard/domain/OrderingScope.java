package com.transactionguard.domain;

/**
 * Scope of the sequence gate. Kept configurable because the right answer depends
 * on the upstream contract: a batch feed usually has one global sequence, while a
 * queue partitioned per account carries a per-account sequence.
 */
public enum OrderingScope {
    GLOBAL,
    PER_ACCOUNT
}
