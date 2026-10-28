package com.transactionguard.processing;

import com.transactionguard.config.TransactionGuardProperties;
import com.transactionguard.domain.OrderingScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void retriesWhileBudgetRemains() {
        RetryPolicy policy = policy(3, Duration.ofMillis(200), 2.0, Duration.ofSeconds(2));

        assertThat(policy.canRetry(1)).isTrue();
        assertThat(policy.canRetry(2)).isTrue();
        assertThat(policy.canRetry(3)).isFalse();
        assertThat(policy.canRetry(4)).isFalse();
    }

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        RetryPolicy policy = policy(10, Duration.ofMillis(200), 2.0, Duration.ofSeconds(2));

        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.backoffFor(3)).isEqualTo(Duration.ofMillis(800));
        assertThat(policy.backoffFor(4)).isEqualTo(Duration.ofMillis(1600));
        assertThat(policy.backoffFor(5)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoffFor(20)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void computesNextAttemptFromTheGivenInstant() {
        RetryPolicy policy = policy(3, Duration.ofMillis(250), 2.0, Duration.ofSeconds(2));

        assertThat(policy.nextAttemptAt(1, NOW)).isEqualTo(NOW.plusMillis(250));
        assertThat(policy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plusMillis(500));
    }

    @Test
    void supportsImmediateRetriesForDemos() {
        RetryPolicy policy = policy(2, Duration.ZERO, 2.0, Duration.ZERO);

        assertThat(policy.nextAttemptAt(1, NOW)).isEqualTo(NOW);
    }

    @Test
    void dueTimeIsNeverAfterTheRequestedInstant() {
        RetryPolicy policy = policy(2, Duration.ZERO, 2.0, Duration.ZERO);
        Instant nanoPreciseNow = Instant.parse("2026-10-27T10:00:00.123456789Z");

        Instant due = policy.nextAttemptAt(1, nanoPreciseNow);

        // Truncated down to the microsecond precision of the database column, so an
        // "immediately due" retry is claimable right away instead of a few
        // nanoseconds in the future.
        assertThat(due).isEqualTo(Instant.parse("2026-10-27T10:00:00.123456Z"));
        assertThat(due).isBeforeOrEqualTo(nanoPreciseNow);
    }

    private static RetryPolicy policy(int maxAttempts, Duration initial, double multiplier, Duration max) {
        return new RetryPolicy(new TransactionGuardProperties(
                new TransactionGuardProperties.Worker(false, 1000, 10, 5),
                new TransactionGuardProperties.Retry(maxAttempts, initial, multiplier, max),
                new TransactionGuardProperties.Ordering(true, OrderingScope.GLOBAL),
                new TransactionGuardProperties.Simulation(Map.of()),
                new TransactionGuardProperties.Ingestion("")));
    }
}
