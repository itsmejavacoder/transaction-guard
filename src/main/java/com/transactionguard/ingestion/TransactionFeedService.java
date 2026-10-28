package com.transactionguard.ingestion;

import com.transactionguard.domain.TransactionRequest;
import com.transactionguard.processing.SequenceGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Feed-level orchestration. Each request gets its own transaction, so one bad record
 * can never roll back the rest of the batch (functional requirement L2-FR-02).
 *
 * <p>Ingestion of one stream is serialised inside the JVM. That is not a throughput
 * compromise for an ordered feed (the sequence gate already only lets one record of a
 * stream be processed at a time) and it removes the self-inflicted race on the
 * {@code stream_cursor} and on the unique keys when a client hammers the same request
 * concurrently. Multi-instance deployments are still protected by those unique keys
 * plus the bounded conflict retry below.</p>
 */
@Service
public class TransactionFeedService {

    private static final Logger log = LoggerFactory.getLogger(TransactionFeedService.class);

    /**
     * A concurrent delivery loses the unique-constraint race. A few short retries give
     * the winning transaction time to commit, after which the idempotency lookup
     * resolves the duplicate without surfacing a database error.
     */
    private static final int MAX_CONFLICT_ATTEMPTS = 10;
    private static final long CONFLICT_RETRY_DELAY_MS = 5L;
    private static final String GLOBAL_STREAM = "GLOBAL";

    private final TransactionIngester ingester;
    private final SequenceGate sequenceGate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> streamLocks = new ConcurrentHashMap<>();

    public TransactionFeedService(TransactionIngester ingester,
                                  SequenceGate sequenceGate,
                                  PlatformTransactionManager transactionManager,
                                  Clock clock) {
        this.ingester = ingester;
        this.sequenceGate = sequenceGate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public FeedIngestionReport ingest(TransactionSource source) {
        Instant startedAt = clock.instant();
        List<SubmissionOutcome> outcomes = ingestAll(source.readAll());
        FeedIngestionReport report = FeedIngestionReport.of(source.description(), startedAt,
                clock.instant(), outcomes);
        log.info("Ingested feed {}: total={} received={} pending={} duplicate={} rejected={}",
                source.description(), report.totalCount(), report.receivedCount(),
                report.pendingCount(), report.duplicateCount(), report.rejectedCount());
        return report;
    }

    public List<SubmissionOutcome> ingestAll(List<TransactionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<SubmissionOutcome> outcomes = new ArrayList<>(requests.size());
        for (TransactionRequest request : requests) {
            outcomes.add(ingestOne(request));
        }
        return List.copyOf(outcomes);
    }

    /**
     * Submits one request in its own transaction. Deliveries of the same stream are
     * serialised and a uniqueness race with another instance is retried a bounded
     * number of times; on a retry the idempotency check finds the winner and returns a
     * duplicate outcome.
     */
    public SubmissionOutcome ingestOne(TransactionRequest request) {
        ReentrantLock lock = streamLocks.computeIfAbsent(streamKeyOf(request), key -> new ReentrantLock());
        lock.lock();
        try {
            return ingestWithConflictRetry(request);
        } finally {
            lock.unlock();
        }
    }

    private SubmissionOutcome ingestWithConflictRetry(TransactionRequest request) {
        RuntimeException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_CONFLICT_ATTEMPTS; attempt++) {
            try {
                SubmissionOutcome outcome = transactionTemplate.execute(status -> ingester.ingest(request));
                if (outcome != null) {
                    return outcome;
                }
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException ex) {
                lastConflict = ex;
                log.debug("Concurrent conflict on submission (attempt {}/{}): {}",
                        attempt, MAX_CONFLICT_ATTEMPTS, ex.getMessage());
                if (attempt < MAX_CONFLICT_ATTEMPTS) {
                    pauseBeforeRetry();
                }
            }
        }
        log.warn("Could not resolve concurrent submission after {} attempts for requestId={}: {}",
                MAX_CONFLICT_ATTEMPTS,
                request == null ? "<null>" : request.requestId(),
                lastConflict == null ? "no outcome" : lastConflict.getMessage());
        return ingester.concurrentConflict(request);
    }

    private String streamKeyOf(TransactionRequest request) {
        if (request == null || request.accountId() == null || request.accountId().isBlank()) {
            return GLOBAL_STREAM;
        }
        return sequenceGate.streamIdFor(request.accountId());
    }

    private static void pauseBeforeRetry() {
        try {
            Thread.sleep(CONFLICT_RETRY_DELAY_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
