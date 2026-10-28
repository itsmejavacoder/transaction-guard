package com.transactionguard.api;

import com.transactionguard.domain.TransactionNotFoundException;
import com.transactionguard.domain.TransactionRecord;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.persistence.LedgerEntryRepository;
import com.transactionguard.persistence.TransactionRecordRepository;
import com.transactionguard.persistence.TransactionTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side service. Keeps controllers thin and gives the API a single place where
 * entity-to-view mapping happens.
 */
@Service
public class TransactionQueryService {

    private static final int MAX_PAGE_SIZE = 500;

    private final TransactionRecordRepository transactions;
    private final LedgerEntryRepository ledger;
    private final TransactionTransitionRepository transitions;

    public TransactionQueryService(TransactionRecordRepository transactions,
                                   LedgerEntryRepository ledger,
                                   TransactionTransitionRepository transitions) {
        this.transactions = transactions;
        this.ledger = ledger;
        this.transitions = transitions;
    }

    @Transactional(readOnly = true)
    public TransactionDetail findByTransactionId(String transactionId) {
        TransactionRecord record = transactions.findByTransactionId(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        return toDetail(record);
    }

    @Transactional(readOnly = true)
    public List<TransactionDetail> list(TransactionStatus status, String accountId, int limit) {
        int effectiveLimit = Math.clamp(limit, 1, MAX_PAGE_SIZE);

        List<TransactionRecord> records;
        if (status != null && accountId != null && !accountId.isBlank()) {
            records = transactions.findAllByOrderByReceivedAtAscIdAsc().stream()
                    .filter(record -> record.getStatus() == status)
                    .filter(record -> record.getAccountId().equals(accountId))
                    .limit(effectiveLimit)
                    .toList();
        } else if (status != null) {
            records = transactions.findByStatusOrderBySequenceNumberAsc(status).stream()
                    .limit(effectiveLimit)
                    .toList();
        } else if (accountId != null && !accountId.isBlank()) {
            records = transactions.findByAccountIdOrderBySequenceNumberAsc(accountId).stream()
                    .limit(effectiveLimit)
                    .toList();
        } else {
            records = transactions.findAllByOrderByReceivedAtAscIdAsc().stream()
                    .limit(effectiveLimit)
                    .toList();
        }
        return records.stream().map(this::toDetail).toList();
    }

    private TransactionDetail toDetail(TransactionRecord record) {
        return new TransactionDetail(
                record.getRequestId(),
                record.getTransactionId(),
                record.getAccountId(),
                record.getStreamId(),
                record.getTransactionType(),
                record.getAmount(),
                record.getCurrency(),
                record.getSequenceNumber(),
                record.getEventTime(),
                record.getOriginalTransactionId(),
                record.getStatus(),
                record.getAttemptCount(),
                record.getNextAttemptAt(),
                record.getFailureCode(),
                record.getFailureMessage(),
                record.isEffectApplied(),
                record.getReceivedAt(),
                record.getUpdatedAt(),
                ledger.findByTransactionId(record.getTransactionId()).map(LedgerEntryView::from).orElse(null),
                transitions.findByTransactionIdOrderByOccurredAtAscIdAsc(record.getTransactionId()).stream()
                        .map(TransitionView::from)
                        .toList());
    }
}
