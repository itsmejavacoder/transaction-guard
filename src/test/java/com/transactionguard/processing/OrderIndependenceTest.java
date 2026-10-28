package com.transactionguard.processing;

import com.transactionguard.domain.TransactionRequest;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.ingestion.JsonFileTransactionSource;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strongest idempotency/ordering statement: whatever permutation of the feed we
 * receive, the end state is identical.
 */
class OrderIndependenceTest extends IntegrationTestSupport {

    @Test
    @DisplayName("eight random arrival orders all converge to the same state")
    void finalStateIsIndependentOfArrivalOrder() {
        List<TransactionRequest> feed = new JsonFileTransactionSource(
                new ClassPathResource("samples/sample-transactions.json"), objectMapper).readAll();

        for (int seed = 0; seed < 8; seed++) {
            clearState();

            List<TransactionRequest> shuffled = new ArrayList<>(feed);
            if (seed > 0) {
                Collections.shuffle(shuffled, new Random(seed));
            }

            ingest(shuffled.toArray(TransactionRequest[]::new));
            drainUntilIdle();

            assertThat(transactions.count())
                    .as("seed %d: one record per business transaction", seed)
                    .isEqualTo(5);
            assertThat(ledger.count()).as("seed %d: one ledger entry per processed transaction", seed)
                    .isEqualTo(4);
            assertThat(recordOf("TXN-2001").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
            assertThat(recordOf("TXN-2002").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
            assertThat(recordOf("TXN-2003").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
            assertThat(recordOf("TXN-2004").getStatus()).isEqualTo(TransactionStatus.REJECTED);
            assertThat(recordOf("TXN-2005").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
            assertThat(balanceOf("ACC-201")).as("seed %d", seed).isEqualByComparingTo("85.00");
            assertThat(balanceOf("ACC-203")).as("seed %d", seed).isEqualByComparingTo("500.00");
            assertThat(gateway.totalEffects()).isEqualTo(4);
            assertThat(sequenceGate.currentGaps()).isEmpty();
        }
    }
}
