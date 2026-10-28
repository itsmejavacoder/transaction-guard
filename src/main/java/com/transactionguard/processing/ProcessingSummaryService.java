package com.transactionguard.processing;

import com.transactionguard.domain.AccountBalance;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.persistence.AccountBalanceRepository;
import com.transactionguard.persistence.LedgerEntryRepository;
import com.transactionguard.persistence.TransactionRecordRepository;
import com.transactionguard.persistence.TransactionTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the processing report from the persisted state. Every number is derived
 * from the database, so the report is accurate after a restart as well.
 */
@Service
public class ProcessingSummaryService {

    private final TransactionRecordRepository transactions;
    private final TransactionTransitionRepository transitions;
    private final LedgerEntryRepository ledger;
    private final AccountBalanceRepository balances;
    private final SequenceGate sequenceGate;
    private final Clock clock;

    public ProcessingSummaryService(TransactionRecordRepository transactions,
                                    TransactionTransitionRepository transitions,
                                    LedgerEntryRepository ledger,
                                    AccountBalanceRepository balances,
                                    SequenceGate sequenceGate,
                                    Clock clock) {
        this.transactions = transactions;
        this.transitions = transitions;
        this.ledger = ledger;
        this.balances = balances;
        this.sequenceGate = sequenceGate;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProcessingSummary summarize() {
        Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus status : TransactionStatus.values()) {
            counts.put(status, 0L);
        }
        transactions.countGroupedByStatus()
                .forEach(statusCount -> counts.put(statusCount.status(), statusCount.total()));

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long duplicateAttempts = transitions.countByToStatus(TransactionStatus.DUPLICATE.name());
        long retryAttempts = transitions.countByReason(com.transactionguard.domain.TransitionReason.RETRY_SCHEDULED);

        List<AccountBalanceView> balanceViews = balances.findAll().stream()
                .sorted(Comparator.comparing(AccountBalance::getAccountId))
                .map(balance -> new AccountBalanceView(balance.getAccountId(), balance.getCurrency(),
                        balance.getBalance(), balance.getUpdatedAt()))
                .toList();

        return new ProcessingSummary(
                clock.instant(),
                total,
                Map.copyOf(counts),
                duplicateAttempts,
                retryAttempts,
                ledger.count(),
                ledger.sumBookedAmount() == null ? BigDecimal.ZERO : ledger.sumBookedAmount(),
                balanceViews,
                sequenceGate.currentGaps());
    }
}
