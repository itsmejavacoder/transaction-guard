package com.transactionguard.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactionguard.domain.AccountBalance;
import com.transactionguard.domain.TransactionRecord;
import com.transactionguard.domain.TransactionRequest;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransactionTransition;
import com.transactionguard.ingestion.SubmissionOutcome;
import com.transactionguard.ingestion.TransactionFeedService;
import com.transactionguard.observability.ProcessingMetrics;
import com.transactionguard.persistence.AccountBalanceRepository;
import com.transactionguard.persistence.LedgerEntryRepository;
import com.transactionguard.persistence.StreamCursorRepository;
import com.transactionguard.persistence.TransactionRecordRepository;
import com.transactionguard.persistence.TransactionTransitionRepository;
import com.transactionguard.processing.ProcessingCycleReport;
import com.transactionguard.processing.ProcessingSummaryService;
import com.transactionguard.processing.SequenceGate;
import com.transactionguard.processing.SimulatedSettlementGateway;
import com.transactionguard.processing.TransactionProcessingWorker;
import com.transactionguard.processing.TransactionProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Shared fixture for integration tests. Every test starts from a clean database and
 * a clean simulated gateway so assertions are deterministic.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    @Autowired
    protected TransactionFeedService feedService;
    @Autowired
    protected TransactionProcessingWorker worker;
    @Autowired
    protected TransactionProcessor processor;
    @Autowired
    protected TransactionRecordRepository transactions;
    @Autowired
    protected AccountBalanceRepository balances;
    @Autowired
    protected LedgerEntryRepository ledger;
    @Autowired
    protected StreamCursorRepository cursors;
    @Autowired
    protected TransactionTransitionRepository transitions;
    @Autowired
    protected SimulatedSettlementGateway gateway;
    @Autowired
    protected ProcessingSummaryService summaryService;
    @Autowired
    protected SequenceGate sequenceGate;
    @Autowired
    protected ProcessingMetrics metrics;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected PlatformTransactionManager transactionManager;

    /**
     * Claims a record directly, bypassing the worker. Used to simulate a worker that
     * claimed a record and then died, leaving it in {@code PROCESSING}.
     */
    protected int claim(Long recordId, Instant claimedAt, TransactionStatus... claimable) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Integer updated = template.execute(status -> transactions.claim(recordId,
                TransactionStatus.PROCESSING, EnumSet.copyOf(List.of(claimable)), claimedAt));
        return updated == null ? 0 : updated;
    }

    @BeforeEach
    void resetDatabase() {
        clearState();
    }

    protected void clearState() {
        transitions.deleteAll();
        ledger.deleteAll();
        transactions.deleteAll();
        cursors.deleteAll();
        balances.deleteAll();
        gateway.reset();
    }

    protected List<SubmissionOutcome> ingest(TransactionRequest... requests) {
        return feedService.ingestAll(Arrays.asList(requests));
    }

    protected SubmissionOutcome ingestOne(TransactionRequest request) {
        return feedService.ingestOne(request);
    }

    protected ProcessingCycleReport drain() {
        return worker.runCycle();
    }

    protected void drainUntilIdle() {
        for (int cycle = 0; cycle < 20; cycle++) {
            ProcessingCycleReport report = worker.runCycle();
            if (report.claimedCount() == 0) {
                return;
            }
        }
        throw new IllegalStateException("Processing did not become idle after 20 cycles");
    }

    protected TransactionRecord recordOf(String transactionId) {
        return transactions.findByTransactionId(transactionId)
                .orElseThrow(() -> new AssertionError("Transaction not found: " + transactionId));
    }

    protected BigDecimal balanceOf(String accountId) {
        return balances.findById(accountId)
                .map(AccountBalance::getBalance)
                .orElseThrow(() -> new AssertionError("Account not found: " + accountId));
    }

    protected List<TransactionTransition> auditOf(String transactionId) {
        return transitions.findByTransactionIdOrderByOccurredAtAscIdAsc(transactionId);
    }

    protected long auditCount(String transactionId, String reason) {
        return auditOf(transactionId).stream()
                .filter(transition -> transition.getReason().equals(reason))
                .count();
    }
}
