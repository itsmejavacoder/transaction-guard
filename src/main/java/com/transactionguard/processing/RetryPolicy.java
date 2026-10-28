package com.transactionguard.processing;

import com.transactionguard.config.TransactionGuardProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Exponential backoff with a hard ceiling. Deliberately deterministic (no jitter)
 * so behaviour is reproducible in tests and demos; a production deployment would
 * add jitter to avoid retry storms, which is called out in the design note.
 */
@Component
public class RetryPolicy {

    private final TransactionGuardProperties.Retry config;

    public RetryPolicy(TransactionGuardProperties properties) {
        this.config = properties.retry();
    }

    public boolean canRetry(int attemptsMade) {
        return attemptsMade < config.maxAttempts();
    }

    public int maxAttempts() {
        return config.maxAttempts();
    }

    public Instant nextAttemptAt(int attemptsMade, Instant now) {
        return truncateToStorePrecision(now.plus(backoffFor(attemptsMade)));
    }

    /**
     * The due time is truncated down to the precision the database can store.
     *
     * <p>Databases keep timestamps at microsecond (or coarser) precision and round the
     * sub-precision part, so a due time equal to "now" can be stored a few hundred
     * nanoseconds in the future. The claim query would then refuse to retry although
     * the backoff has already elapsed. Rounding down instead makes "due now" mean
     * exactly that, which matters most for the zero-backoff configuration used by
     * demos and tests.</p>
     */
    private static Instant truncateToStorePrecision(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    Duration backoffFor(int attemptsMade) {
        double factor = Math.pow(config.multiplier(), Math.max(0, attemptsMade - 1));
        long millis = Math.round(config.initialBackoff().toMillis() * factor);
        long capped = Math.min(millis, config.maxBackoff().toMillis());
        return Duration.ofMillis(Math.max(0, capped));
    }
}
