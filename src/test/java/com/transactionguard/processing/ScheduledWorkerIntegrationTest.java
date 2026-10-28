package com.transactionguard.processing;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static com.transactionguard.support.TransactionFixtures.credit;
import static com.transactionguard.support.TransactionFixtures.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the background component really is a background component: no manual
 * trigger is used here, the scheduled worker picks the records up by itself.
 */
@TestPropertySource(properties = {
        "transactionguard.worker.enabled=true",
        "transactionguard.worker.interval-ms=25"
})
class ScheduledWorkerIntegrationTest extends IntegrationTestSupport {

    @Test
    void scheduledWorkerProcessesOutOfOrderFeedAutomatically() {
        ingest(debit("REQ-2", "TXN-2", "ACC-1", "40.00", 2));
        ingest(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
            assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
            assertThat(balanceOf("ACC-1")).isEqualByComparingTo("60.00");
            assertThat(ledger.count()).isEqualTo(2);
        });
    }
}
