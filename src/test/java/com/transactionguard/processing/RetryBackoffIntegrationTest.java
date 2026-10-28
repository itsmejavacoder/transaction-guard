package com.transactionguard.processing;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static com.transactionguard.support.TransactionFixtures.credit;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * With a real backoff configured, the worker must respect the retry window rather
 * than hot-looping on the failed record.
 */
@TestPropertySource(properties = {
        "transactionguard.retry.initial-backoff=1s",
        "transactionguard.retry.max-backoff=1s"
})
class RetryBackoffIntegrationTest extends IntegrationTestSupport {

    @Test
    void retryWaitsForItsScheduledWindow() {
        gateway.failNext("TXN-1", 1);
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        Long recordId = recordOf("TXN-1").getId();

        processor.process(recordId);
        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.RETRY_PENDING);
        assertThat(recordOf("TXN-1").getNextAttemptAt()).isAfter(java.time.Instant.now());

        ProcessingCycleReport immediateCycle = drain();

        assertThat(immediateCycle.claimedCount()).isZero();
        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.RETRY_PENDING);
        assertThat(recordOf("TXN-1").getAttemptCount()).isEqualTo(1);

        // A direct processor call ignores the schedule (used to prove the retry is safe);
        // the scheduled worker would pick it up once the window elapses.
        ProcessingOutcome retried = processor.process(recordId);
        assertThat(retried.status()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(gateway.effectCount("TXN-1")).isEqualTo(1);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("100.00");
    }
}
