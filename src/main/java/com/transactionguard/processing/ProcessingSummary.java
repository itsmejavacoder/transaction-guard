package com.transactionguard.processing;

import com.transactionguard.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Operational snapshot: status counts, duplicate and retry pressure, ledger size,
 * balances and current sequence gaps.
 */
public record ProcessingSummary(Instant generatedAt,
                                long totalTransactions,
                                Map<TransactionStatus, Long> countsByStatus,
                                long duplicateAttempts,
                                long retryAttempts,
                                long ledgerEntries,
                                BigDecimal netBookedAmount,
                                List<AccountBalanceView> balances,
                                List<SequenceGap> sequenceGaps) {

    public long count(TransactionStatus status) {
        return countsByStatus.getOrDefault(status, 0L);
    }

    public long openTransactions() {
        return count(TransactionStatus.RECEIVED)
                + count(TransactionStatus.PENDING_PREDECESSOR)
                + count(TransactionStatus.PROCESSING)
                + count(TransactionStatus.RETRY_PENDING);
    }
}
