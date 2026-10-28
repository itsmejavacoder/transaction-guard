package com.transactionguard.processing;

import com.transactionguard.domain.TransactionRequest;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.ingestion.SubmissionOutcome;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.transactionguard.support.TransactionFixtures.credit;
import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("processing the same record twice applies the effect once")
    void sequentialReprocessingIsSkipped() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        Long recordId = recordOf("TXN-1").getId();

        ProcessingOutcome first = processor.process(recordId);
        ProcessingOutcome second = processor.process(recordId);

        assertThat(first.status()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(first.claimed()).isTrue();
        assertThat(second.claimed()).isFalse();
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("100.00");
        assertThat(gateway.effectCount("TXN-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent workers cannot claim the same record twice")
    void concurrentProcessingHasSingleWinner() throws Exception {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        Long recordId = recordOf("TXN-1").getId();

        List<ProcessingOutcome> outcomes = runConcurrently(2,
                () -> processor.process(recordId));

        assertThat(outcomes).filteredOn(ProcessingOutcome::claimed).hasSize(1);
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("100.00");
        assertThat(gateway.effectCount("TXN-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("twelve concurrent deliveries of one request produce one record and one effect")
    void concurrentDuplicateSubmissionsAreIdempotent() throws Exception {
        TransactionRequest request = credit("REQ-CONCURRENT", "TXN-CONCURRENT", "ACC-1", "250.00", 1);

        List<SubmissionOutcome> outcomes = runConcurrently(12, () -> feedService.ingestOne(request));

        assertThat(outcomes).filteredOn(outcome -> outcome.status() == TransactionStatus.RECEIVED)
                .hasSize(1);
        assertThat(outcomes).filteredOn(SubmissionOutcome::duplicate).hasSize(11);
        assertThat(transactions.count()).isEqualTo(1);

        drainUntilIdle();

        assertThat(recordOf("TXN-CONCURRENT").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("250.00");
        assertThat(gateway.totalEffects()).isEqualTo(1);
        // A concurrent replay may be detected by the request-id or the transaction-id
        // check depending on commit visibility; either way it is one duplicate attempt.
        long duplicateAudits = auditCount("TXN-CONCURRENT", TransitionReason.DUPLICATE_REQUEST)
                + auditCount("TXN-CONCURRENT", TransitionReason.DUPLICATE_TRANSACTION);
        assertThat(duplicateAudits).isEqualTo(11);
    }

    @Test
    @DisplayName("ambiguity between delivery id and business id is resolved by business id")
    void differentRequestIdSameTransactionIsOneEffect() {
        SubmissionOutcome first = ingestOne(credit("REQ-A", "TXN-1", "ACC-1", "100.00", 1));
        SubmissionOutcome second = ingestOne(credit("REQ-B", "TXN-1", "ACC-1", "100.00", 1));

        assertThat(first.status()).isEqualTo(TransactionStatus.RECEIVED);
        assertThat(second.status()).isEqualTo(TransactionStatus.DUPLICATE);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.transactionId()).isEqualTo("TXN-1");

        drain();
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("100.00");
    }

    private <T> List<T> runConcurrently(int threads, ThrowingSupplier<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return action.get();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>(threads);
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
