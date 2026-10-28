package com.transactionguard.observability;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.persistence.TransactionRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Operational counters exposed through {@code /actuator/metrics}. Every intake and
 * processing outcome increments exactly one counter, which makes the Grafana-style
 * questions ("how many duplicates did we reject today?") answerable without parsing
 * logs.
 */
@Component
public class ProcessingMetrics {

    private final Counter accepted;
    private final Counter duplicates;
    private final Counter rejected;
    private final Counter failed;
    private final Counter processed;
    private final Counter retryScheduled;
    private final Counter claimSkipped;

    public ProcessingMetrics(MeterRegistry registry, TransactionRecordRepository transactions) {
        this.accepted = counter(registry, "accepted");
        this.duplicates = counter(registry, "duplicate");
        this.rejected = counter(registry, "rejected");
        this.failed = counter(registry, "failed");
        this.processed = counter(registry, "processed");
        this.retryScheduled = counter(registry, "retry_scheduled");
        this.claimSkipped = counter(registry, "claim_skipped");

        for (TransactionStatus status : TransactionStatus.values()) {
            Gauge.builder("transactionguard.transactions.current", transactions,
                            repository -> repository.countByStatus(status))
                    .description("Current number of transactions in a given status")
                    .tag("status", status.name())
                    .register(registry);
        }
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("transactionguard.transactions.outcomes")
                .description("Transaction intake and processing outcomes")
                .tag("outcome", outcome)
                .register(registry);
    }

    public void transactionAccepted() {
        accepted.increment();
    }

    public void duplicateDetected() {
        duplicates.increment();
    }

    public void transactionRejected() {
        rejected.increment();
    }

    public void transactionFailed() {
        failed.increment();
    }

    public void transactionProcessed() {
        processed.increment();
    }

    public void retryScheduled() {
        retryScheduled.increment();
    }

    public void claimSkipped() {
        claimSkipped.increment();
    }

    public double acceptedCount() {
        return accepted.count();
    }

    public double duplicateCount() {
        return duplicates.count();
    }

    public double rejectedCount() {
        return rejected.count();
    }

    public double processedCount() {
        return processed.count();
    }

    public double retryScheduledCount() {
        return retryScheduled.count();
    }

    public double failedCount() {
        return failed.count();
    }
}
