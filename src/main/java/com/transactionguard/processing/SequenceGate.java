package com.transactionguard.processing;

import com.transactionguard.config.TransactionGuardProperties;
import com.transactionguard.domain.OrderingScope;
import com.transactionguard.domain.StreamCursor;
import com.transactionguard.domain.TransactionRecord;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.persistence.StreamCursorRepository;
import com.transactionguard.persistence.TransactionRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Sequence-based ordering strategy (Option A of the assessment).
 *
 * <p>Each stream has a watermark: the next sequence number that may be processed.
 * A record arriving early is parked as {@code PENDING_PREDECESSOR}; when the gap is
 * filled, the gate releases it and the worker can claim it. A record arriving after
 * its sequence was already passed is rejected as stale unless it is a replay, which
 * is caught earlier by the idempotency checks.</p>
 *
 * <p>Trade-off (documented in {@code docs/DESIGN.md}): head-of-line blocking is
 * bounded by treating every terminal state (PROCESSED, REJECTED, FAILED) as
 * "sequence consumed". A permanently bad record therefore delays but never
 * deadlocks the stream, and the gap report shows exactly what is missing.</p>
 */
@Component
public class SequenceGate {

    private static final Logger log = LoggerFactory.getLogger(SequenceGate.class);
    private static final long FIRST_SEQUENCE = 1L;
    private static final int MAX_REPORTED_MISSING = 100;

    private final StreamCursorRepository cursors;
    private final TransactionRecordRepository transactions;
    private final TransactionTransitionRecorder recorder;
    private final TransactionGuardProperties properties;
    private final Clock clock;

    public SequenceGate(StreamCursorRepository cursors,
                        TransactionRecordRepository transactions,
                        TransactionTransitionRecorder recorder,
                        TransactionGuardProperties properties,
                        Clock clock) {
        this.cursors = cursors;
        this.transactions = transactions;
        this.recorder = recorder;
        this.properties = properties;
        this.clock = clock;
    }

    public String streamIdFor(String accountId) {
        return properties.ordering().scope() == OrderingScope.PER_ACCOUNT ? accountId : "GLOBAL";
    }

    /**
     * Decides the initial status of a new record and records the decision. Must run in
     * the ingestion transaction so the sequence reservation and the record commit
     * together.
     *
     * <p>A stale delivery cannot be persisted: its sequence slot is already owned by a
     * different record and the {@code (stream_id, sequence_number)} constraint is the
     * invariant that makes ordering safe. The caller therefore receives
     * {@code recorded=false} and answers the caller with a rejection instead.</p>
     */
    @Transactional
    public AdmissionDecision admit(TransactionRecord record) {
        if (!properties.ordering().enabled()) {
            return AdmissionDecision.accepted(record.getStatus(), "Ordering disabled");
        }

        Instant now = clock.instant();
        StreamCursor cursor = lockOrCreateCursor(record.getStreamId(), now);
        long expected = cursor.getNextExpectedSequence();
        long sequence = record.getSequenceNumber();

        if (sequence == expected) {
            recorder.record(record, null, TransactionStatus.RECEIVED,
                    TransitionReason.INGESTED_READY, "Sequence " + sequence + " is next in stream");
            return AdmissionDecision.accepted(TransactionStatus.RECEIVED,
                    "Sequence " + sequence + " is next in stream");
        }
        if (sequence > expected) {
            record.markPendingPredecessor(now);
            transactions.save(record);
            recorder.record(record, TransactionStatus.RECEIVED, TransactionStatus.PENDING_PREDECESSOR,
                    TransitionReason.INGESTED_PENDING,
                    "Waiting for sequence " + expected + " before " + sequence);
            return AdmissionDecision.accepted(TransactionStatus.PENDING_PREDECESSOR,
                    "Waiting for sequence " + expected + " before " + sequence);
        }

        String detail = "Sequence " + sequence + " was already passed (next expected " + expected + ")";
        recorder.record(record, null, TransactionStatus.REJECTED,
                TransitionReason.STALE_SEQUENCE, detail);
        log.info("Rejected stale transactionId={} sequence={} stream={} nextExpected={}",
                record.getTransactionId(), sequence, record.getStreamId(), expected);
        return AdmissionDecision.rejected(TransitionReason.STALE_SEQUENCE, detail);
    }

    private StreamCursor lockOrCreateCursor(String streamId, Instant now) {
        Optional<StreamCursor> existing = cursors.findForUpdate(streamId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Callers that ingest the same stream are serialised (see TransactionFeedService),
        // so within one instance this insert cannot race with itself. A multi-instance
        // race is still caught by the unique key and resolved by the feed retry loop.
        StreamCursor created = new StreamCursor(streamId, FIRST_SEQUENCE, now);
        return cursors.saveAndFlush(created);
    }

    /**
     * Moves the watermark over terminal records and releases the next waiting record.
     * Called after every terminal transition (processed, rejected, failed) and after
     * ingestion of a pre-rejected row. The cursor is created on demand so that a
     * rejection arriving before the first admissible record still advances the stream.
     */
    @Transactional
    public void advanceAndRelease(String streamId) {
        if (!properties.ordering().enabled()) {
            return;
        }

        Instant now = clock.instant();
        StreamCursor cursor = lockOrCreateCursor(streamId, now);
        boolean changed = false;

        while (true) {
            long expected = cursor.getNextExpectedSequence();
            Optional<TransactionRecord> maybeRecord =
                    transactions.findFirstByStreamIdAndSequenceNumber(streamId, expected);
            if (maybeRecord.isEmpty()) {
                break;
            }

            TransactionRecord record = maybeRecord.get();
            if (record.getStatus().isTerminal()) {
                cursor.advance(now);
                changed = true;
                continue;
            }

            if (record.getStatus() == TransactionStatus.PENDING_PREDECESSOR) {
                record.releaseForProcessing(now);
                transactions.save(record);
                recorder.record(record, TransactionStatus.PENDING_PREDECESSOR, TransactionStatus.RECEIVED,
                        TransitionReason.RELEASED, "Predecessors complete; sequence " + expected + " released");
                log.info("Released transaction {} (sequence {}) in stream {}",
                        record.getTransactionId(), expected, streamId);
                changed = true;
            }
            break;
        }

        if (changed) {
            cursors.save(cursor);
        }
    }

    /**
     * Reports holes that are currently blocking a stream. This is the operational
     * answer to "why is nothing moving?".
     *
     * <p>A sequence only counts as missing when no record (in any status) occupies it:
     * a record that is merely waiting is present, it is just not terminal yet.</p>
     */
    @Transactional(readOnly = true)
    public List<SequenceGap> currentGaps() {
        List<SequenceGap> gaps = new ArrayList<>();
        for (StreamCursor cursor : cursors.findAll()) {
            Optional<TransactionRecord> firstPending = transactions
                    .findFirstByStreamIdAndStatusOrderBySequenceNumberAsc(
                            cursor.getStreamId(), TransactionStatus.PENDING_PREDECESSOR);
            if (firstPending.isEmpty()) {
                continue;
            }

            long expected = cursor.getNextExpectedSequence();
            long pendingSequence = firstPending.get().getSequenceNumber();
            if (pendingSequence <= expected) {
                continue;
            }

            Set<Long> present = new HashSet<>(
                    transactions.findSequenceNumbersByStreamId(cursor.getStreamId()));
            List<Long> missing = new ArrayList<>();
            for (long sequence = expected; sequence < pendingSequence; sequence++) {
                if (!present.contains(sequence)) {
                    missing.add(sequence);
                    if (missing.size() >= MAX_REPORTED_MISSING) {
                        break;
                    }
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            gaps.add(new SequenceGap(cursor.getStreamId(), missing.get(0), pendingSequence,
                    missing.size(), List.copyOf(missing)));
        }
        return List.copyOf(gaps);
    }
}
