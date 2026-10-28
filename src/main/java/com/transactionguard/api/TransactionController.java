package com.transactionguard.api;

import com.transactionguard.domain.TransactionRequest;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.ingestion.SubmissionOutcome;
import com.transactionguard.ingestion.TransactionFeedService;
import com.transactionguard.processing.TransactionRecoveryService;
import com.transactionguard.validation.RequestValidationException;
import com.transactionguard.validation.ValidationIssue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Ingestion and query API.
 *
 * <p>Status codes are meaningful for the caller:</p>
 * <ul>
 *   <li>202 - accepted (ready or waiting for a predecessor)</li>
 *   <li>200 - replay/duplicate, original state returned, no new effect</li>
 *   <li>400 - structurally invalid request, nothing persisted</li>
 *   <li>409 - the payload is valid but the stream position it asks for is already taken</li>
 *   <li>422 - well-formed but rejected by a validation or business rule</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Ingestion, query and operator recovery")
public class TransactionController {

    /** Refusals caused by stream state rather than by the payload itself. */
    private static final Set<String> STREAM_CONFLICT_CODES = Set.of(
            TransitionReason.STALE_SEQUENCE,
            TransitionReason.SEQUENCE_CONFLICT);

    private final TransactionFeedService feedService;
    private final TransactionQueryService queryService;
    private final TransactionRecoveryService recoveryService;

    public TransactionController(TransactionFeedService feedService,
                                 TransactionQueryService queryService,
                                 TransactionRecoveryService recoveryService) {
        this.feedService = feedService;
        this.queryService = queryService;
        this.recoveryService = recoveryService;
    }

    @Operation(summary = "Submit one transaction", description = "Validates, deduplicates and admits the record to its stream. Returns 202 when persisted, 200 for a duplicate, 400/409/422 for refusals.")
    @PostMapping
    public ResponseEntity<SubmissionOutcome> submit(@RequestBody(required = false) TransactionRequest request) {
        SubmissionOutcome outcome = feedService.ingestOne(request);
        return ResponseEntity.status(statusFor(outcome)).body(outcome);
    }

    @Operation(summary = "Submit a batch", description = "Ingests each record in its own transaction and returns 207 with per-record outcomes.")
    @PostMapping("/batch")
    public ResponseEntity<BatchSubmissionResponse> submitBatch(
            @RequestBody(required = false) TransactionBatchRequest batch) {
        if (batch == null || batch.transactions().isEmpty()) {
            throw new RequestValidationException(List.of(ValidationIssue.of("transactions", "BATCH_EMPTY",
                    "At least one transaction is required")));
        }
        return ResponseEntity.status(HttpStatus.MULTI_STATUS)
                .body(BatchSubmissionResponse.of(feedService.ingestAll(batch.transactions())));
    }

    /**
     * Operator action: moves a {@code FAILED} transaction back into the retry pipeline
     * with a fresh retry budget. The record keeps its sequence slot and is processed
     * out of stream order, which is the deliberate cost of not deadlocking the stream
     * on a failure. The requeue itself is written to the audit trail.
     */
    @Operation(summary = "Requeue a failed transaction", description = "Operator recovery: FAILED to RETRY_PENDING with a fresh retry budget. The record keeps its sequence slot and is processed out of stream order.")
    @PostMapping("/{transactionId}/requeue")
    public ResponseEntity<TransactionDetail> requeue(@PathVariable String transactionId) {
        recoveryService.requeue(transactionId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(queryService.findByTransactionId(transactionId));
    }

    @Operation(summary = "Get one transaction", description = "Current state, ledger evidence and the full audit history.")
    @GetMapping("/{transactionId}")
    public TransactionDetail find(@PathVariable String transactionId) {
        return queryService.findByTransactionId(transactionId);
    }

    @Operation(summary = "List transactions", description = "Filter by status and account, capped by limit.")
    @GetMapping
    public List<TransactionDetail> list(@RequestParam(required = false) TransactionStatus status,
                                        @RequestParam(required = false) String accountId,
                                        @RequestParam(defaultValue = "100") int limit) {
        return queryService.list(status, accountId, limit);
    }

    private static HttpStatus statusFor(SubmissionOutcome outcome) {
        if (!outcome.recorded() && !outcome.validationIssues().isEmpty()) {
            // A refusal that is caused by the stream (its sequence is already used or was
            // already passed) is a conflict, not a malformed request. If any structural
            // problem is also present, the payload itself is at fault and 400 wins.
            boolean streamConflict = outcome.validationIssues().stream()
                    .allMatch(issue -> STREAM_CONFLICT_CODES.contains(issue.code()));
            return streamConflict ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        }
        if (outcome.duplicate()) {
            return HttpStatus.OK;
        }
        return switch (outcome.status()) {
            case RECEIVED, PENDING_PREDECESSOR -> HttpStatus.ACCEPTED;
            case REJECTED, FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.OK;
        };
    }
}
