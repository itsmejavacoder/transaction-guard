package com.transactionguard.processing;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.ingestion.FeedIngestionReport;
import com.transactionguard.ingestion.JsonFileTransactionSource;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the exact scenario from the assessment brief through the real components.
 */
class SampleScenarioIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("the assessment sample ends with 4 processed, 1 rejected, 1 duplicate and 1 retry")
    void reproducesTheAssessmentSample() {
        gateway.failNext("TXN-2005", 1);
        double processedBefore = metrics.processedCount();
        double duplicatesBefore = metrics.duplicateCount();
        double rejectedBefore = metrics.rejectedCount();
        double retriesBefore = metrics.retryScheduledCount();

        FeedIngestionReport ingestion = ingestSample();

        assertThat(ingestion.totalCount()).isEqualTo(6);
        assertThat(ingestion.receivedCount()).isEqualTo(1);
        assertThat(ingestion.pendingCount()).isEqualTo(3);
        assertThat(ingestion.duplicateCount()).isEqualTo(1);
        assertThat(ingestion.rejectedCount()).isEqualTo(1);

        ProcessingCycleReport cycle = drain();

        assertThat(cycle.processedCount()).isEqualTo(4);
        assertThat(cycle.retryScheduledCount()).isEqualTo(1);
        assertThat(cycle.failedCount()).isZero();
        assertThat(cycle.claimedCount()).isEqualTo(5);

        assertThat(recordOf("TXN-2001").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-2002").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-2003").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-2004").getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(recordOf("TXN-2004").getFailureCode()).isEqualTo(TransitionReason.VALIDATION_FAILED);
        assertThat(recordOf("TXN-2005").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-2005").getAttemptCount()).isEqualTo(2);

        assertThat(balanceOf("ACC-201")).isEqualByComparingTo("85.00");
        assertThat(balanceOf("ACC-203")).isEqualByComparingTo("500.00");
        assertThat(balances.findById("ACC-202")).isEmpty();

        List<BigDecimal> bookedBalances = ledger.findAll().stream()
                .sorted((left, right) -> left.getId().compareTo(right.getId()))
                .map(entry -> entry.getBalanceAfter())
                .toList();
        assertThat(bookedBalances).containsExactly(
                new BigDecimal("100.00"),
                new BigDecimal("60.00"),
                new BigDecimal("85.00"),
                new BigDecimal("500.00"));

        ProcessingSummary summary = summaryService.summarize();
        assertThat(summary.totalTransactions()).isEqualTo(5);
        assertThat(summary.count(TransactionStatus.PROCESSED)).isEqualTo(4);
        assertThat(summary.count(TransactionStatus.REJECTED)).isEqualTo(1);
        assertThat(summary.openTransactions()).isZero();
        assertThat(summary.duplicateAttempts()).isEqualTo(1);
        assertThat(summary.retryAttempts()).isEqualTo(1);
        assertThat(summary.ledgerEntries()).isEqualTo(4);
        assertThat(summary.netBookedAmount()).isEqualByComparingTo("585.00");
        assertThat(summary.sequenceGaps()).isEmpty();
        assertThat(summary.balances())
                .extracting(AccountBalanceView::accountId)
                .containsExactly("ACC-201", "ACC-203");

        assertThat(metrics.processedCount() - processedBefore).isEqualTo(4.0);
        assertThat(metrics.duplicateCount() - duplicatesBefore).isEqualTo(1.0);
        assertThat(metrics.rejectedCount() - rejectedBefore).isEqualTo(1.0);
        assertThat(metrics.retryScheduledCount() - retriesBefore).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a second ingestion of the same feed is fully idempotent")
    void replayingTheWholeFeedChangesNothing() {
        ingestSample();
        drain();
        BigDecimal balanceAfterFirstRun = balanceOf("ACC-201");
        long ledgerAfterFirstRun = ledger.count();

        FeedIngestionReport replay = ingestSample();
        drain();

        assertThat(replay.duplicateCount()).isEqualTo(6);
        assertThat(replay.receivedCount()).isZero();
        assertThat(replay.rejectedCount()).isZero();
        assertThat(transactions.count()).isEqualTo(5);
        assertThat(ledger.count()).isEqualTo(ledgerAfterFirstRun);
        assertThat(balanceOf("ACC-201")).isEqualByComparingTo(balanceAfterFirstRun);
        assertThat(gateway.totalEffects()).isEqualTo(4);
    }

    @Test
    @DisplayName("transaction history tells the whole story, including the retry")
    void auditTrailExplainsEachTransaction() {
        gateway.failNext("TXN-2005", 1);
        ingestSample();
        drain();

        assertThat(auditOf("TXN-2005"))
                .extracting(transition -> transition.getReason())
                .containsSubsequence(TransitionReason.CLAIMED, TransitionReason.RETRY_SCHEDULED,
                        TransitionReason.CLAIMED, TransitionReason.PROCESSED);
        assertThat(auditCount("TXN-2002", TransitionReason.DUPLICATE_TRANSACTION)).isEqualTo(1);
        assertThat(auditOf("TXN-2004"))
                .extracting(transition -> transition.getReason())
                .contains(TransitionReason.VALIDATION_FAILED);
    }

    private FeedIngestionReport ingestSample() {
        Resource resource = new ClassPathResource("samples/sample-transactions.json");
        return feedService.ingest(new JsonFileTransactionSource(resource, objectMapper));
    }
}
