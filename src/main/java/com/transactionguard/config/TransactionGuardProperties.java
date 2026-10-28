package com.transactionguard.config;

import com.transactionguard.domain.OrderingScope;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * Central, strongly typed configuration for the service.
 *
 * <p>Everything that a production operator may want to tune (worker cadence,
 * retry budget, ordering strategy, fault simulation, startup feed) is exposed
 * here instead of being hard-coded.</p>
 */
@ConfigurationProperties(prefix = "transactionguard")
public record TransactionGuardProperties(
        Worker worker,
        Retry retry,
        Ordering ordering,
        Simulation simulation,
        Ingestion ingestion,
        Recovery recovery) {

    public TransactionGuardProperties {
        worker = worker == null ? Worker.defaults() : worker;
        retry = retry == null ? Retry.defaults() : retry;
        ordering = ordering == null ? Ordering.defaults() : ordering;
        simulation = simulation == null ? Simulation.defaults() : simulation;
        ingestion = ingestion == null ? Ingestion.defaults() : ingestion;
        recovery = recovery == null ? Recovery.defaults() : recovery;
    }

    /**
     * Background worker cadence and safety limits.
     *
     * @param enabled            whether the scheduled worker is active
     * @param intervalMs         delay between scheduled cycles
     * @param maxRecordsPerCycle hard cap per cycle, protects the instance from a runaway loop
     * @param fetchSize          number of claimable ids fetched per batch
     */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("250") long intervalMs,
            @DefaultValue("500") int maxRecordsPerCycle,
            @DefaultValue("25") int fetchSize) {

        static Worker defaults() {
            return new Worker(true, 250, 500, 25);
        }

        public Worker {
            if (intervalMs < 0) {
                throw new IllegalArgumentException("transactionguard.worker.interval-ms must be >= 0");
            }
            if (maxRecordsPerCycle < 1) {
                throw new IllegalArgumentException("transactionguard.worker.max-records-per-cycle must be >= 1");
            }
            fetchSize = Math.max(1, fetchSize);
        }
    }

    /**
     * Retry budget and exponential backoff.
     *
     * @param maxAttempts    total attempts allowed per transaction (including the first one)
     * @param initialBackoff backoff after the first failure
     * @param multiplier     growth factor per attempt
     * @param maxBackoff     hard ceiling for the backoff
     */
    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("200ms") Duration initialBackoff,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("2s") Duration maxBackoff) {

        static Retry defaults() {
            return new Retry(3, Duration.ofMillis(200), 2.0, Duration.ofSeconds(2));
        }

        public Retry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("transactionguard.retry.max-attempts must be >= 1");
            }
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("transactionguard.retry.multiplier must be >= 1.0");
            }
        }
    }

    /**
     * Ordering strategy. {@code GLOBAL} treats the whole feed as one ordered stream,
     * {@code PER_ACCOUNT} tracks an independent sequence per account.
     */
    public record Ordering(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("GLOBAL") OrderingScope scope) {

        static Ordering defaults() {
            return new Ordering(true, OrderingScope.GLOBAL);
        }
    }

    /**
     * Deterministic fault injection used to demonstrate retry behaviour.
     *
     * @param transientFailures transactionId to number of initial attempts that fail
     */
    public record Simulation(
            @DefaultValue Map<String, Integer> transientFailures) {

        static Simulation defaults() {
            return new Simulation(Map.of());
        }

        public Simulation {
            transientFailures = transientFailures == null ? Map.of() : Map.copyOf(transientFailures);
        }
    }

    /**
     * Optional feed to ingest at startup (classpath: or file: location).
     */
    public record Ingestion(@DefaultValue("") String startupFile) {

        static Ingestion defaults() {
            return new Ingestion("");
        }
    }

    /**
     * Recovery of claims abandoned by a crashed or partitioned worker.
     *
     * @param enabled           run the startup sweep and the scheduled sweep
     * @param staleClaimTimeout how long a record may stay {@code PROCESSING} before it
     *                          is considered abandoned
     * @param intervalMs        delay between scheduled sweeps
     */
    public record Recovery(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5m") Duration staleClaimTimeout,
            @DefaultValue("60000") long intervalMs) {

        static Recovery defaults() {
            return new Recovery(true, Duration.ofMinutes(5), 60_000L);
        }

        public Recovery {
            if (staleClaimTimeout == null || staleClaimTimeout.isZero() || staleClaimTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "transactionguard.recovery.stale-claim-timeout must be greater than zero");
            }
            if (intervalMs < 1) {
                throw new IllegalArgumentException(
                        "transactionguard.recovery.interval-ms must be >= 1");
            }
        }
    }
}
