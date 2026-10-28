# Requirement Traceability

Mapping between the assessment requirement set (FDE L2, *Idempotent Background Transaction
Processing Service*) and this implementation. Every row points at code and at a test that
fails if the behaviour regresses.

---

## 1. Functional requirements (§9.4)

| ID | Requirement | Implementation | Evidence |
|---|---|---|---|
| L2-FR-01 | Accept requests from a configurable or replaceable input source | `TransactionSource` interface with `JsonFileTransactionSource` and `InMemoryTransactionSource`; HTTP ingestion via `TransactionController`; startup replay configured by `transactionguard.ingestion.startup-file` | `JsonFileTransactionSourceTest` (5), `TransactionApiIntegrationTest` (21) |
| L2-FR-02 | Validate mandatory fields and reject malformed records | `TransactionRequestValidator` (all findings collected, not fail-fast); `TransactionIngester` persists trackable rejected rows and refuses untrackable ones | `TransactionRequestValidatorTest` (12), `IngestionIntegrationTest.invalidRecordIsRejectedAndAudited` |
| L2-FR-03 | Maintain transaction processing state | `TransactionRecord` persisted with `request_id`/`transaction_id`/`(stream_id, sequence_number)` unique constraints; `TransactionTransition` append-only audit | `IngestionIntegrationTest`, `OrderingIntegrationTest` |
| L2-FR-04 | Status lifecycle (`RECEIVED`, `PROCESSING`, `PROCESSED`, `DUPLICATE`, `FAILED`, `RETRY_PENDING`) | `TransactionStatus` enum + `TransactionLifecycle` guarded transitions (`IllegalStatusTransitionException`); `FAILED` has exactly one successor, the operator requeue | `TransactionLifecycleTest` (5), `RetryIntegrationTest`, `TransactionApiIntegrationTest` |
| L2-FR-05 | Process each unique business transaction once | Atomic JPQL claim; unique `transaction_id`; unique `ledger_entry.transaction_id`; idempotent settlement gateway keyed by transaction id | `IdempotencyIntegrationTest` (4) |
| L2-FR-06 | Detect duplicates even when they arrive later in the batch | Duplicate lookup by `request_id` then `transaction_id` before validation and before persistence; audit row per attempt | `IngestionIntegrationTest`, `IdempotencyIntegrationTest`, `SampleScenarioIntegrationTest` |
| L2-FR-07 | Define and implement an out-of-order strategy | **Option A**: sequence cursor per stream, `PENDING_PREDECESSOR` buffering, release on predecessor terminal, stale rejection, gap report | `OrderingIntegrationTest` (7), `PerAccountOrderingIntegrationTest` (2), `OrderIndependenceTest` (1) |
| L2-FR-08 | Simulate at least one retryable failure scenario | `SimulatedSettlementGateway` with per-transaction fault injection (`transactionguard.simulation.transient-failures`); demo injects one failure for `TXN-2005` | `RetryIntegrationTest`, `SampleScenarioIntegrationTest`, demo log |
| L2-FR-09 | Retry transient failures without duplicate effects | `RetryPolicy` (bounded, exponential, due-time rounded down); effect key idempotency; record-level claim; ledger uniqueness | `RetryIntegrationTest` (5), `RetryBackoffIntegrationTest` (1), `IdempotencyIntegrationTest` |
| L2-FR-10 | Separate business logic from input handling | Packages `api`, `ingestion`, `validation`, `processing`, `persistence`, `observability`; the processor has no HTTP or JSON knowledge | code structure + package-private seams |
| L2-FR-11 | Produce a processing summary with counts by status | `GET /api/v1/processing/summary` (`ProcessingSummaryService`) with counts, duplicate/retry pressure, ledger size, net booked amount, balances, gaps; `DUPLICATE` is documented as an API outcome and always `0` in stored counts | `TransactionApiIntegrationTest.summaryReportsCountsAndGaps`, demo output |
| L2-FR-12 | Tests for duplicate, out-of-order, invalid and retry scenarios | 95 tests across 17 classes | `mvn test` (see `SAMPLE_RUN.md` §3) |

---

## 2. Non-functional requirements (§6)

| NFR | Requirement | How it is met | Evidence |
|---|---|---|---|
| 6.1 | Idempotency | three duplicate-recognition levels + atomic claim + idempotent effect + unique ledger | `IdempotencyIntegrationTest` |
| 6.2 | Ordering | sequence cursor, pending state, deterministic conflict handling, gap reporting | `OrderingIntegrationTest`, `OrderIndependenceTest` |
| 6.3 | Resilience | transient/business classification, bounded exponential retry, terminal `FAILED`, audit; failed records are recoverable through operator requeue and an abandoned-claim sweep | `RetryIntegrationTest`, `RetryBackoffIntegrationTest`, `RecoveryIntegrationTest` |
| 6.4 | Data consistency | one DB transaction per effect; claim before work; ledger + balance + record saved together; unique constraints | `ReversalIntegrationTest`, `IdempotencyIntegrationTest` |
| 6.5 | Observability | structured logs with request ids and masked accounts; Micrometer counters/gauges; summary and gap endpoints; audit trail | §8 of `DESIGN.md`, `TransactionApiIntegrationTest` |
| 6.6 | Security and data handling | input validation before persistence; identifier/amount/currency patterns; `SensitiveDataMasker` for account ids; no secrets in repo; safe error bodies (`ApiExceptionHandler`) | `TransactionRequestValidatorTest`, `SensitiveDataMaskerTest` |
| 6.7 | Maintainability | one responsibility per class, explicit naming, no Lombok magic, tests per behaviour | package layout, test matrix |

---

## 3. Out-of-order expectations (§9.5)

Option A was chosen: **sequence-based processing**.

| Expectation | Implementation |
|---|---|
| Each request has a sequence number | required field, validated (`SEQUENCE_REQUIRED`, `SEQUENCE_INVALID`) |
| Future sequences are marked pending | `PENDING_PREDECESSOR` persisted and audited (`INGESTED_PENDING_PREDECESSOR`) |
| Processed when prior sequences are available | `SequenceGate.advanceAndRelease` on every terminal transition (`RELEASED_AFTER_PREDECESSOR`) |
| Missing sequences are reported | `GET /api/v1/processing/sequence-gaps` (first missing, blocked pending, list of holes) |

Trade-off (documented in `DESIGN.md` §5): strict order implies head-of-line blocking, which
is bounded by treating every terminal state — including `REJECTED` and `FAILED` — as
consuming its sequence, and by bounded retries. `Option B` (state machine) is present too,
as the `TransactionLifecycle` guard, but it is not the ordering mechanism because it cannot
distinguish "not yet valid" from "missing". `Option C` (latest state by event time) was
rejected because clock skew and equal timestamps make the order non-deterministic, and the
domain is a feed of financial postings where "last write wins" is unsafe.

---

## 4. Resilience expectations (§9.6)

| Expectation | Implementation |
|---|---|
| Transient failure for one or more records | `SimulatedSettlementGateway` fault counters; demo config injects one failure for `TXN-2005` |
| Retry with a maximum retry count | `RetryPolicy` (`max-attempts: 3`, exponential backoff, cap) |
| No duplicate effect after retry | gateway effect key + claim + ledger unique constraint; `gateway.effectCount == 1` asserted |
| Failed state after exhaustion | `FAILED` + `RETRY_EXHAUSTED` audit, cursor advanced, `failed` counter incremented |
| Failed records recoverable (NFR 6.3) | `POST /transactions/{id}/requeue` (`MANUAL_REQUEUE`, fresh retry budget); stale `PROCESSING` claims swept back to `RETRY_PENDING` after `recovery.stale-claim-timeout`, or `FAILED`/`STALE_CLAIM_EXHAUSTED` if the budget is spent; `POST /processing/recover-stale` for an immediate sweep |
| Clear logging / reporting | transition logs, `ProcessingCycleReport`, `/processing/summary` |

---

## 5. Acceptance criteria (§9.7)

| Criterion | Where it is demonstrated |
|---|---|
| Duplicates do not cause duplicate side effects | `SampleScenarioIntegrationTest` (ACC-201 = 85.00), `IdempotencyIntegrationTest` (12 threads → one effect) |
| Out-of-order requests follow a defined strategy | `OrderingIntegrationTest.outOfOrderArrivalIsBuffered`, `ScheduledWorkerIntegrationTest` |
| Retry is safe and idempotent | `RetryIntegrationTest.workerDrivesRetryToCompletion` (attempts 2, effects 1) |
| Failed records are recoverable | `RecoveryIntegrationTest`, `TransactionApiIntegrationTest.requeueFailedTransactionReturns202` |
| Invalid records are isolated | `SampleScenarioIntegrationTest` (`TXN-2004` rejected, others processed), `IngestionIntegrationTest` batch isolation |
| Clear status lifecycle | `TransactionStatus` / `TransactionLifecycle`, exposed in `GET /transactions/{id}` history |
| Production implications explained | `DESIGN.md` §7 (concurrency), §8 (observability), §12 (trade-offs), §13 (hardening) |
| AI usage explained and validated | `AI_USAGE.md`, including the defects AI introduced and how they were caught |
| Trade-offs and next steps identified | `DESIGN.md` §11–13 |

---

## 6. Suggested test data (§9.8)

`src/main/resources/samples/sample-transactions.json` is exactly the assessment table, in the
assessment order, with `eventTime` added.

| # | transactionId | requestId | Seq | Account | Type | Amount | Observed result |
|---|---|---|---|---|---|---|---|
| 1 | TXN-2002 | REQ-2 | 2 | ACC-201 | DEBIT | 40 | `PENDING_PREDECESSOR` → released → `PROCESSED` |
| 2 | TXN-2001 | REQ-1 | 1 | ACC-201 | CREDIT | 100 | `PROCESSED` |
| 3 | TXN-2002 | REQ-2-DUP | 2 | ACC-201 | DEBIT | 40 | duplicate suppressed (`DUPLICATE_TRANSACTION`), no debit |
| 4 | TXN-2003 | REQ-3 | 3 | ACC-201 | CREDIT | 25 | `PROCESSED` |
| 5 | TXN-2004 | REQ-4 | 4 | ACC-202 | DEBIT | -10 | `REJECTED` / `VALIDATION_FAILED`, sequence consumed |
| 6 | TXN-2005 | REQ-5 | 5 | ACC-203 | CREDIT | 500 | retry once, then `PROCESSED` |

Final state: 4 processed, 1 rejected, 1 duplicate attempt, 1 retry, 4 ledger entries,
net booked `585.00`, `ACC-201` = `85.00`, `ACC-203` = `500.00`, no gaps.

---

## 7. Deliverables (§9.9)

| Deliverable | Location |
|---|---|
| Source code | `src/main/java/com/transactionguard` |
| Execution instructions | `README.md` §4–5, `run-demo.cmd` / `run-demo.sh`, `run-tests.cmd` / `run-tests.sh` |
| Sample input | `src/main/resources/samples/sample-transactions.json`, `docs/samples/` |
| Sample output | `README.md` §5, `docs/SAMPLE_RUN.md` |
| Unit tests / evidence | `src/test/java`, `SAMPLE_RUN.md` §3 |
| Design note | `docs/DESIGN.md` |
| AI usage | `docs/AI_USAGE.md` |

---

## 8. Assumptions and deliberate exclusions

**Assumptions**

1. The feed is ordered per stream by `sequenceNumber`, contiguous from 1. This is the
   assessment's Option A and the sample data conforms.
2. `transactionId` is the business key owned by the upstream core system; `requestId` is the
   delivery id. A replay may change the delivery id but never the business id.
3. Amounts are positive; direction comes from `transactionType`. A negative amount is a
   malformed record (route 400/422), which is how the assessment's `-10` row is handled.
4. Funds are checked at processing time, not ingestion time, because balance is only
   meaningful in stream order.
5. A `REJECTED` or `FAILED` record consumes its sequence: the stream moves on, and the
   rejected row remains visible with its audit trail. A `FAILED` record can be explicitly
   requeued by an operator, but it keeps its old sequence slot and is processed out of
   stream order.

**Deliberately out of scope** (and listed as production next steps in `DESIGN.md` §13):
real settlement integration, authentication/authorisation, distributed scheduling,
multi-region deployment, UI, outbox to other systems, operator UI/CLI for the recovery
endpoints.
