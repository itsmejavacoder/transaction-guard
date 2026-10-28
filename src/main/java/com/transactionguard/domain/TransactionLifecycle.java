package com.transactionguard.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Guard rail around the transaction status machine. Every status change on an
 * existing record must be a declared, legal transition; anything else indicates a
 * bug or a concurrent writer and fails fast.
 */
public final class TransactionLifecycle {

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED = new EnumMap<>(TransactionStatus.class);

    static {
        ALLOWED.put(TransactionStatus.RECEIVED,
                EnumSet.of(TransactionStatus.PROCESSING, TransactionStatus.PENDING_PREDECESSOR,
                        TransactionStatus.REJECTED, TransactionStatus.FAILED));
        ALLOWED.put(TransactionStatus.PENDING_PREDECESSOR,
                EnumSet.of(TransactionStatus.RECEIVED, TransactionStatus.REJECTED, TransactionStatus.FAILED));
        ALLOWED.put(TransactionStatus.PROCESSING,
                EnumSet.of(TransactionStatus.PROCESSED, TransactionStatus.RETRY_PENDING,
                        TransactionStatus.REJECTED, TransactionStatus.FAILED));
        ALLOWED.put(TransactionStatus.RETRY_PENDING,
                EnumSet.of(TransactionStatus.PROCESSING, TransactionStatus.FAILED, TransactionStatus.REJECTED));
        ALLOWED.put(TransactionStatus.PROCESSED, EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(TransactionStatus.DUPLICATE, EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(TransactionStatus.REJECTED, EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(TransactionStatus.FAILED, EnumSet.noneOf(TransactionStatus.class));
    }

    private TransactionLifecycle() {
    }

    public static boolean isAllowed(TransactionStatus from, TransactionStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void require(TransactionStatus from, TransactionStatus to) {
        if (!isAllowed(from, to)) {
            throw new IllegalStatusTransitionException(from, to);
        }
    }

    public static Set<TransactionStatus> successorsOf(TransactionStatus status) {
        return Set.copyOf(ALLOWED.getOrDefault(status, Set.of()));
    }
}
