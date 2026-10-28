package com.transactionguard.ingestion;

import com.transactionguard.domain.AccountBalance;
import com.transactionguard.domain.TransactionRecord;
import com.transactionguard.domain.TransactionRequest;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.observability.ProcessingMetrics;
import com.transactionguard.observability.SensitiveDataMasker;
import com.transactionguard.persistence.AccountBalanceRepository;
import com.transactionguard.persistence.TransactionRecordRepository;
import com.transactionguard.processing.AdmissionDecision;
import com.transactionguard.processing.SequenceGate;
import com.transactionguard.processing.TransactionTransitionRecorder;
import com.transactionguard.validation.TransactionRequestValidator;
import com.transactionguard.validation.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Validates and admits a single request. Always runs inside a transaction opened by
 * {@link TransactionFeedService} so that sequence reservation and record creation
 * commit atomically.
 */
@Component
public class TransactionIngester {

    private static final Logger log = LoggerFactory.getLogger(TransactionIngester.class);

    private final TransactionRecordRepository transactions;
    private final AccountBalanceRepository balances;
    private final TransactionRequestValidator validator;
    private final SequenceGate sequenceGate;
    private final TransactionTransitionRecorder recorder;
    private final ProcessingMetrics metrics;
    private final SensitiveDataMasker masker;
    private final Clock clock;

    public TransactionIngester(TransactionRecordRepository transactions,
                               AccountBalanceRepository balances,
                               TransactionRequestValidator validator,
                               SequenceGate sequenceGate,
                               TransactionTransitionRecorder recorder,
                               ProcessingMetrics metrics,
                               SensitiveDataMasker masker,
                               Clock clock) {
        this.transactions = transactions;
        this.balances = balances;
        this.validator = validator;
        this.sequenceGate = sequenceGate;
        this.recorder = recorder;
        this.metrics = metrics;
        this.masker = masker;
        this.clock = clock;
    }

    public SubmissionOutcome ingest(TransactionRequest request) {
        Instant now = clock.instant();

        // 1. Request-level idempotency: an exact delivery replay is answered from the
        //    already persisted record without touching any balance.
        if (request != null && hasText(request.requestId())) {
            Optional<TransactionRecord> replay = transactions.findByRequestId(request.requestId());
            if (replay.isPresent()) {
                return duplicate(replay.get(), request.requestId(), TransitionReason.DUPLICATE_REQUEST, now);
            }
        }

        // 2. Business-level idempotency: same transaction, different delivery id. Checked
        //    before validation so a replay of an already-seen (even rejected) business
        //    transaction can never create a second record.
        if (request != null && hasText(request.transactionId())) {
            Optional<TransactionRecord> known = transactions.findByTransactionId(request.transactionId());
            if (known.isPresent()) {
                return duplicate(known.get(), request.requestId(), TransitionReason.DUPLICATE_TRANSACTION, now);
            }
        }

        // 3. Structural validation. All problems are reported at once.
        List<ValidationIssue> issues = validator.validate(request);
        if (!issues.isEmpty()) {
            return rejectInvalid(request, issues, now);
        }

        String streamId = sequenceGate.streamIdFor(request.accountId());

        // 4. One sequence slot per account; a different transaction on an occupied slot
        //    is an upstream defect and is rejected without disturbing the stream.
        if (transactions.existsByAccountIdAndSequenceNumber(request.accountId(), request.sequence())) {
            return rejectUntracked(request, issues, "SEQUENCE_CONFLICT",
                    "Sequence " + request.sequence() + " is already used in stream " + streamId, now);
        }

        // 5. A single account may only hold one currency.
        Optional<AccountBalance> balance = balances.findById(request.accountId());
        if (balance.isPresent() && !balance.get().getCurrency().equals(request.currency())) {
            List<ValidationIssue> businessIssues = List.of(ValidationIssue.of("currency",
                    "CURRENCY_MISMATCH",
                    "Account is held in " + balance.get().getCurrency() + ", request is in " + request.currency()));
            TransactionRecord rejected = TransactionRecord.rejected(request, streamId, request.sequence(),
                    "CURRENCY_MISMATCH", summarize(businessIssues), now);
            return persistRejected(rejected, businessIssues, new SubmissionOutcome(
                    request.requestId(), request.transactionId(), TransactionStatus.REJECTED, false, true,
                    "Rejected: currency mismatch", businessIssues, now, request.sequence(), streamId), now);
        }

        // 6. Ordering admission and persistence.
        TransactionRecord record = TransactionRecord.admitted(request, streamId, request.sequence(),
                TransactionStatus.RECEIVED, now);
        AdmissionDecision decision = sequenceGate.admit(record);

        if (!decision.recorded()) {
            metrics.transactionRejected();
            log.info("Rejected stale transaction transactionId={} sequence={} stream={} code={}",
                    record.getTransactionId(), record.getSequenceNumber(), streamId, decision.code());
            return new SubmissionOutcome(request.requestId(), request.transactionId(),
                    TransactionStatus.REJECTED, false, false, decision.detail(),
                    List.of(ValidationIssue.of("sequenceNumber", decision.code(), decision.detail())),
                    now, request.sequence(), streamId);
        }

        transactions.save(record);
        TransactionStatus admittedStatus = decision.status();

        metrics.transactionAccepted();
        String message = admittedStatus == TransactionStatus.PENDING_PREDECESSOR
                ? "Accepted; waiting for earlier sequence in stream " + streamId
                : "Accepted for processing";
        log.info("Accepted transaction transactionId={} account={} sequence={} status={}",
                record.getTransactionId(), masker.maskAccountId(record.getAccountId()),
                record.getSequenceNumber(), admittedStatus);
        return outcome(record, false, message, List.of(), now);
    }

    private SubmissionOutcome rejectInvalid(TransactionRequest request, List<ValidationIssue> issues, Instant now) {
        metrics.transactionRejected();
        String detail = summarize(issues);

        if (!validator.isTrackable(request)) {
            log.info("Rejected malformed request requestId={} issues={}",
                    request == null ? "<null>" : request.requestId(), detail);
            return new SubmissionOutcome(
                    request == null ? null : request.requestId(),
                    request == null ? null : request.transactionId(),
                    TransactionStatus.REJECTED, false, false,
                    "Rejected by validation", issues, now, 0, null);
        }

        String streamId = sequenceGate.streamIdFor(request.accountId());
        if (transactions.existsByAccountIdAndSequenceNumber(request.accountId(), request.sequence())) {
            return rejectUntracked(request, issues, "SEQUENCE_CONFLICT",
                    "Sequence " + request.sequence() + " is already used in stream " + streamId, now);
        }

        TransactionRecord rejected = TransactionRecord.rejected(request, streamId, request.sequence(),
                TransitionReason.VALIDATION_FAILED, detail, now);
        SubmissionOutcome outcome = new SubmissionOutcome(request.requestId(), request.transactionId(),
                TransactionStatus.REJECTED, false, true, "Rejected by validation", issues, now,
                request.sequence(), streamId);
        return persistRejected(rejected, issues, outcome, now);
    }

    private SubmissionOutcome persistRejected(TransactionRecord rejected, List<ValidationIssue> issues,
                                              SubmissionOutcome outcome, Instant now) {
        transactions.saveAndFlush(rejected);
        recorder.record(rejected, null, TransactionStatus.REJECTED,
                rejected.getFailureCode(), summarize(issues));
        // A permanently bad record still consumes its sequence: it is terminal, so it
        // must not block every later record in the stream.
        sequenceGate.advanceAndRelease(rejected.getStreamId());
        log.info("Rejected transaction transactionId={} code={}",
                rejected.getTransactionId(), rejected.getFailureCode());
        return outcome;
    }

    private SubmissionOutcome rejectUntracked(TransactionRequest request, List<ValidationIssue> issues,
                                              String code, String detail, Instant now) {
        metrics.transactionRejected();
        List<ValidationIssue> allIssues = new ArrayList<>(issues);
        allIssues.add(ValidationIssue.of("sequenceNumber", code, detail));
        log.info("Rejected transaction transactionId={} code={} detail={}",
                request.transactionId(), code, detail);
        return new SubmissionOutcome(request.requestId(), request.transactionId(),
                TransactionStatus.REJECTED, false, false, "Rejected: " + detail, allIssues, now,
                request.sequence(), null);
    }

    private SubmissionOutcome duplicate(TransactionRecord existing, String incomingRequestId,
                                        String reason, Instant now) {
        metrics.duplicateDetected();
        recorder.record(existing.getTransactionId(), incomingRequestId, existing.getStatus(),
                TransactionStatus.DUPLICATE, reason,
                "Replay suppressed; original status is " + existing.getStatus(), now);
        log.info("Duplicate suppressed transactionId={} requestId={} reason={}",
                existing.getTransactionId(), incomingRequestId, reason);
        return new SubmissionOutcome(incomingRequestId, existing.getTransactionId(),
                TransactionStatus.DUPLICATE, true, true,
                "Duplicate ignored; original state is " + existing.getStatus(),
                List.of(), now, existing.getSequenceNumber(), existing.getStreamId());
    }

    private SubmissionOutcome outcome(TransactionRecord record, boolean duplicate, String message,
                                      List<ValidationIssue> issues, Instant now) {
        return new SubmissionOutcome(record.getRequestId(), record.getTransactionId(), record.getStatus(),
                duplicate, true, message, issues, now, record.getSequenceNumber(), record.getStreamId());
    }

    /**
     * Used by the feed service when a concurrent writer won a uniqueness race and the
     * record cannot be located afterwards.
     */
    public SubmissionOutcome concurrentConflict(TransactionRequest request) {
        Instant now = clock.instant();
        metrics.transactionRejected();
        log.warn("Concurrent modification detected while ingesting requestId={}",
                request == null ? "<null>" : request.requestId());
        return new SubmissionOutcome(request == null ? null : request.requestId(),
                request == null ? null : request.transactionId(),
                TransactionStatus.REJECTED, false, false,
                "Concurrent submission detected; the request was not applied. Please retry.",
                List.of(ValidationIssue.of("request", "CONCURRENT_MODIFICATION",
                        "Another submission changed the same record concurrently")),
                now, request == null ? 0 : request.sequence(), null);
    }

    private static String summarize(List<ValidationIssue> issues) {
        return issues.stream()
                .map(issue -> issue.field() + ": " + issue.message())
                .collect(Collectors.joining("; "));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
