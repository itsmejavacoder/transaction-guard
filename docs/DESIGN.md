# TransactionGuard — Design Note

This note explains *why* the service is built the way it is. It covers the architecture,
the idempotency argument, the ordering algorithm, retry and failure handling,
observability, the trade-offs that were consciously accepted, and what would change for a
production deployment.

---

## 1. Problem and scope

The upstream core-banking platform emits transaction feeds that are:

* **unordered** — a record with sequence 2 can arrive before sequence 1;
* **at-least-once** — the same business transaction can be delivered more than once, often
  with a different delivery/request id;
* **partially bad** — some records are structurally invalid and must not stop the rest;
* **failure-prone** — the downstream settlement call can time out and must be retried.

The service must apply each business transaction **exactly once**, keep per-stream order,
reject bad data without corrupting state, and make every decision auditable.

In scope: ingestion API, persistent state machine, background processing, retry, duplicate
and out-of-order handling, operational visibility, automated tests.

Out of scope (called out in §13): real settlement integration, authentication, multi-region
deployment, UI.

---

## 2. Architecture

### 2.1 Components

```
                 ┌──────────────────────────────────────────────────────────┐
 HTTP clients ──►│ api/                                                     │
                 │  TransactionController    ProcessingController           │
                 │  RequestTracingFilter     ApiExceptionHandler            │
                 └───────────────┬──────────────────────────────────────────┘
                                 │
                 ┌───────────────▼──────────────────────────────────────────┐
                 │ ingestion/                                               │
                 │  TransactionFeedService   (one tx per request, per-stream │
                 │  TransactionIngester       serialisation, conflict retry) │
                 │  JsonFileTransactionSource, StartupFeedIngestionRunner    │
                 └───────┬──────────────────────────────┬───────────────────┘
                         │                              │
        ┌────────────────▼─────────────┐   ┌────────────▼──────────────────┐
        │ validation/                  │   │ processing/SequenceGate        │
        │  TransactionRequestValidator │   │  admission, cursor, gaps       │
        └──────────────────────────────┘   └────────────┬──────────────────┘
                                                        │
                 ┌──────────────────────────────────────▼──────────────────┐
                 │ persistence/                                            │
                 │  TransactionRecordRepository (atomic claim)             │
                 │  StreamCursorRepository (pessimistic lock)              │
                 │  AccountBalanceRepository (pessimistic lock)            │
                 │  LedgerEntryRepository, TransactionTransitionRepository │
                 └──────────────────────────────┬──────────────────────────┘
                                                │
                 ┌──────────────────────────────▼──────────────────────────┐
                 │ processing/ (background)                                │
                 │  TransactionProcessingWorker  @Scheduled cycle          │
                 │  TransactionProcessor         claim → effect → book     │
                 │  TransactionRecoveryService   requeue / stale claims    │
                 │  StaleClaimRecoveryRunner     startup sweep             │
                 │  RetryPolicy, SimulatedSettlementGateway                │
                 │  TransactionTransitionRecorder (audit)                  │
                 └─────────────────────────────────────────────────────────┘
```

### 2.2 Why these boundaries

| Boundary | Reason |
|---|---|
| `api` separate from `ingestion` | HTTP concerns (status codes, request ids, error shapes) never leak into domain logic |
| `validation` separate from `processing` | malformed data is rejected before it can touch money, and the same validator powers the "can this rejected row occupy a sequence?" decision |
| `SequenceGate` separate from `TransactionProcessor` | ordering is a policy; processing is the effect. They can be tested and changed independently |
| `SettlementGateway` interface | the real downstream system can be swapped in without touching the processor; the simulator proves retry idempotency deterministically |
| `persistence` repositories | all concurrency-critical SQL lives in explicit `@Query` methods, so the atomicity argument is readable in one place |

### 2.3 Ingestion flow (single record)

1. `TransactionFeedService.ingestOne` acquires the per-stream lock, then opens one
   transaction (`TransactionTemplate`), so a failure in one record never aborts a batch.
2. `TransactionIngester`:
   1. rejects a `null` request;
   2. **duplicate check** — `request_id`, then `transaction_id`;
   3. **validation** — structural issues collected, not fail-fast;
   4. if the row is untrackable (missing request/transaction/account id or sequence) it is
      returned as rejected and **not persisted**;
   5. otherwise the row is persisted as `REJECTED` (audit `VALIDATION_FAILED`);
   6. sequence admission via `SequenceGate.admit`;
   7. persist with the admitted status and record the audit transition.
3. The response reports status, whether a record was created, and all validation issues.

### 2.4 Processing flow (single record)

```
claim (atomic UPDATE)  ──►  PROCESSING
      │
      ├─ effect already applied?  ── yes ──►  PROCESSED (defensive invariant)
      │
      ├─ resolve signed amount (credit / debit / reversal rules)
      ├─ lock account balance, check currency + sufficient funds
      ├─ settlement gateway (idempotent by effect key)
      ├─ write ledger entry (unique per transaction id)
      ├─ update balance, mark record PROCESSED
      └─ advance sequence cursor, release next waiting record
```

Any `TransientSettlementException` becomes `RETRY_PENDING` (or `FAILED` when the budget is
exhausted). Any `BusinessRuleViolationException` becomes `REJECTED` immediately.

---

## 3. Domain model

### 3.1 Entities

| Entity | Purpose | Key constraints |
|---|---|---|
| `TransactionRecord` | aggregate root: request data, status, attempt count, failure info | `request_id` unique, `transaction_id` unique, `(stream_id, sequence_number)` unique |
| `TransactionTransition` | append-only audit of every state change | none beyond id |
| `StreamCursor` | per-stream watermark `next_expected_sequence` | primary key `stream_id`, `@Version` |
| `LedgerEntry` | financial evidence of an applied effect | `transaction_id` unique |
| `AccountBalance` | current balance per account/currency | primary key `account_id` |

`TransactionStatus` is an explicit state machine (`TransactionLifecycle`). `PROCESSED`,
`REJECTED` and `DUPLICATE` are terminal. `FAILED` is terminal for the automatic pipeline;
the single exception is an operator requeue back to `RETRY_PENDING`. Illegal transitions
throw `IllegalStatusTransitionException` instead of silently corrupting state.

### 3.2 Status lifecycle

```
                 ┌──────────────► REJECTED        (validation, business rule, stale sequence)
RECEIVED ────────┼──► PROCESSING ──► PROCESSED
   ▲             │        │
   │             │        └──► RETRY_PENDING ──► PROCESSING ──► FAILED
   │             │                 ▲                              │
   │             │                 └── transient settlement       │ operator requeue
   │             │                     failure / stale claim      │ (MANUAL_REQUEUE)
   │             │                                                ▼
   │             └──────────────────────────────────────── RETRY_PENDING
   │
PENDING_PREDECESSOR ──(predecessor terminal)──┘
```

`DUPLICATE` exists as an API outcome; replayed requests are not stored as records. The
duplicate *attempt* is stored as an audit row on the original record, which keeps "how many
times did upstream resend this?" answerable.

### 3.3 Constraints are the real guarantee

Application checks can race; database constraints cannot. The unique keys are therefore the
authority:

* `request_id` — a delivery replay with the same delivery id;
* `transaction_id` — a replay with a new delivery id but the same business transaction;
* `(stream_id, sequence_number)` — two different records claiming the same position;
* `ledger_entry.transaction_id` — the last line of defence against a double booking.

---

## 4. Idempotency

### 4.1 Three recognition levels

| Level | Lookup | Example | Outcome |
|---|---|---|---|
| 1 | `request_id` | retry of the same HTTP call | `DUPLICATE_REQUEST`, no new effect |
| 2 | `transaction_id` | same business transaction, new delivery id | `DUPLICATE_TRANSACTION`, no new effect |
| 3 | `(stream_id, sequence_number)` | new transaction reusing a consumed slot | `SEQUENCE_CONFLICT` / `STALE_SEQUENCE` refusal (`409`, not persisted) |

Level 3 is a *conflict*, not a duplicate: the slot is already owned by a different record,
and the `(stream_id, sequence_number)` constraint means a second row cannot be stored.
The caller gets `409` with code `STALE_SEQUENCE` and nothing is written.

### 4.2 Why duplicate detection runs before validation

A replay of a valid, already-processed record carries the same payload — validating it
again is wasted work and can produce a misleading answer if the rules changed since the
original was accepted (for example, a newly introduced currency rule would turn a harmless
replay into a `422`). Business idempotency wins: if the platform has already seen this
transaction, report the original outcome.

The reverse case is also handled: if the original was untrackable and therefore never
persisted, a replay is judged again and still reported as invalid.

### 4.3 Exactly-once effect

Three mechanisms compose:

1. **Atomic claim.**
   ```sql
   UPDATE transaction_record
      SET status = 'PROCESSING', attempt_count = attempt_count + 1, updated_at = ?
    WHERE id = ?
      AND status IN ('RECEIVED', 'RETRY_PENDING')
   ```
   Exactly one caller gets `1`; everyone else gets `0` and skips. No read-modify-write race,
   no optimistic-lock retry storm.

2. **Idempotent external effect.** The settlement instruction carries an effect key (the
   business transaction id). The gateway records effects by key, so calling it twice has
   one effect. This closes the classic gap: *effect succeeded, DB commit failed*.

3. **Local uniqueness.** `ledger_entry.transaction_id` is unique and the record carries
   `effect_applied`. Even if a bug let two workers through, the second insert fails and the
   transaction rolls back rather than double-booking.

### 4.4 Failure matrix

| Failure point | State after crash | Recovery |
|---|---|---|
| before claim | `RECEIVED` / `RETRY_PENDING` | next cycle claims it |
| during processing, before commit | transaction rolls back to the pre-claim status; an external effect may already exist | next cycle re-claims it; the gateway suppresses the duplicate effect by key |
| after commit | record, ledger/balance and cursor are committed together | nothing to do |
| `PROCESSING` row found later (defensive) | abandoned/incomplete claim | scheduled/startup stale-claim sweep recovers claims older than the visibility timeout to `RETRY_PENDING`, or `FAILED` if the budget is spent; see §6.5 |

The important design decision is that **the external effect happens before the local
booking**, inside the processing transaction, and the gateway is idempotent. A rollback or
crash after the call therefore leads to a safe retry rather than a double settlement. The
alternative (book first, settle after) would require compensating transactions. The
stale-claim sweep is defence in depth: in the normal path the claim and the booking share
one transaction, so a crash rolls the claim back; any `PROCESSING` row that is nevertheless
left behind is recovered after the visibility timeout.

### 4.5 Concurrent duplicate submissions

Twelve threads submitting the identical request are a realistic replay-storm test. Three
layers cooperate:

* ingestion of one stream is serialised in-process (see §7.1), which removes the
  self-inflicted race on the cursor and on the unique keys;
* unique constraints still arbitrate if another instance races;
* `TransactionFeedService` retries a lost unique-key race up to 10 times with a 5 ms
  pause, after which the idempotency lookup sees the winner's committed row and returns
  `DUPLICATE`.

The integration test asserts: 1 accepted, 11 duplicates, 1 record, 1 ledger entry, 1
gateway effect, 12 audit rows.

---

## 5. Out-of-order handling

### 5.1 Chosen strategy: per-stream sequence cursor

Each stream has a watermark: `next_expected_sequence`.

* `sequence == expected` → `RECEIVED`, claimable immediately;
* `sequence > expected` → `PENDING_PREDECESSOR`, persisted but not claimable;
* `sequence < expected` → stale, rejected, **not persisted**.

When a record reaches a terminal state (`PROCESSED`, `REJECTED`, `FAILED`), the gate advances
the cursor over consecutive terminal records and releases the first waiting record
(`PENDING_PREDECESSOR` → `RECEIVED`).

### 5.2 Why sequence instead of event time

| Criterion | Sequence | Event time |
|---|---|---|
| Uniqueness of the order | total, no ties | clock skew and ties |
| Gap detection | exact (integer holes) | approximate |
| Replay determinism | identical | depends on clock |
| Upstream contract | explicit field in the feed | implicit |

Sequence is the only field that makes "what is missing?" answerable without guessing, so it
is the ordering key and the API requires it (`SEQUENCE_REQUIRED`). Event time is still
stored and exposed for business reporting.

### 5.3 Why a stale record is not persisted

A stale delivery's sequence slot is already owned by a different record. Persisting it
would either violate `(stream_id, sequence_number)` or force a status that consumes a slot
it never had. Two refusals cover the cases, both with `recorded=false`:

| Code | When | HTTP |
|---|---|---|
| `SEQUENCE_CONFLICT` | the same account already has a record on that sequence number | `409` |
| `STALE_SEQUENCE` | the stream watermark has passed the sequence (for example the slot was consumed by a rejected record of another account in a global stream) | `409` |

Nothing is written to `transaction_record`, so the slot and the cursor are untouched.
A stale refusal is still written to the audit trail (the recorder keys transitions by
transaction id, not by foreign key), which is what lets an operator answer "why was this
record not accepted?" without application logs. A sequence-slot conflict is answered
synchronously and logged, but no audit row is written because the request never owned the
slot.

### 5.4 Gap reporting

`GET /api/v1/processing/sequence-gaps` answers "why is nothing moving?". A sequence counts
as missing only if **no record in any status** occupies it — a `PENDING_PREDECESSOR` row is
present, just not terminal. The report returns the stream id, the first missing sequence,
the blocked pending sequence, and the list of holes (capped at 100 per stream).

This distinction matters operationally: a stream can be blocked by an *existing but
retrying* record (no gap, retry pending) or by a *never-delivered* record (gap). The two
need different reactions.

### 5.5 Head-of-line blocking

Per-stream ordering implies head-of-line blocking by definition. The design bounds it:

* every terminal state consumes the sequence, so a permanently bad record delays the
  stream but never deadlocks it;
* retries are bounded, so a `RETRY_PENDING` record becomes `FAILED` after `max-attempts`;
* ingestion of untrackable rows does not consume a sequence, so garbage cannot create
  permanent holes;
* `sequence-gaps` makes the remaining blocking explicit.

If throughput mattered more than order, the alternative would be to process within an
account concurrently and only enforce order at the ledger-posting step. That is a product
decision, not a technical one, and is listed in §13.

### 5.6 Ordering scope

`transactionguard.ordering.scope`:

* `GLOBAL` (default) — one stream for the whole feed, the strictest interpretation of the
  assessment;
* `PER_ACCOUNT` — one stream per account, allowing accounts to progress independently.

The stream id is stored on the record (`GLOBAL` or the account id), so switching scope is a
configuration change plus data migration, not a code change. `PerAccountOrderingIntegrationTest`
proves the per-account mode with two accounts advancing independently.

---

## 6. Retry and failure handling

### 6.1 Classification first

| Exception | Meaning | Handling |
|---|---|---|
| `TransientSettlementException` | downstream timeout / 5xx | retry with backoff |
| `BusinessRuleViolationException` | insufficient funds, currency mismatch, reversal rules | terminal `REJECTED`, gateway never called |
| structural `ValidationIssue`s | bad payload | terminal `REJECTED` at ingestion |
| unexpected `RuntimeException` | bug | `ProcessingFailureHandler` marks `FAILED` with `UNEXPECTED_PROCESSING_ERROR`, audit `RETRY_EXHAUSTED`, stream advanced |

Retrying a business failure would be wrong: the answer will not change, and the stream would
stall for nothing.

### 6.2 Backoff

`RetryPolicy` computes `delay = min(initial × multiplier^(attempt-1), max)`, deterministic
(no jitter) so tests and demos are reproducible. Jitter is a production item (§13).

`nextAttemptAt` is **truncated down to microsecond precision** before it is stored. This is
not cosmetic: H2 (and most databases) store timestamps at microsecond or coarser precision
and round the sub-precision part. With a `0 ms` backoff the due time equals "now", and
rounding up by a few hundred nanoseconds made the record briefly *not due*, so a worker
cycle that re-polled at the same clock instant skipped it. Rounding down makes "due now"
mean exactly that. The bug was caught by the full test suite and is covered by
`RetryPolicyTest.dueTimeIsNeverAfterTheRequestedInstant`.

### 6.3 Budget and terminal state

With `max-attempts: 3`:

| Attempt | Result |
|---|---|
| 1 | transient → `RETRY_PENDING`, next attempt after `200ms` |
| 2 | transient → `RETRY_PENDING`, next attempt after `400ms` |
| 3 | transient → `FAILED`, `RETRY_EXHAUSTED` audit, cursor advances |

`FAILED` is terminal for the automatic pipeline and visible in the summary. It is *not*
automatically resurrected: reviving it is an explicit operator action (see §6.5), which keeps
the "did we lose money?" question answerable. The requeue is audited and resets the retry
budget; the record keeps its original sequence slot and is processed out of stream order,
which is the documented cost of not deadlocking the stream on a permanent failure.

### 6.4 Failure isolation

* One bad record never rolls back a batch: each submission has its own transaction.
* A failed record never blocks its stream: `FAILED` consumes the sequence.
* An unexpected exception in one record does not abort the cycle: the worker catches it,
  marks the record and continues with the next id.

### 6.5 Recoverability (NFR 6.3)

Traceability alone is not recovery. Two mechanisms close the loop, both reusing the existing
idempotent claim and gateway:

**Operator requeue.** `POST /api/v1/transactions/{id}/requeue` moves a `FAILED` record back to
`RETRY_PENDING` with `attemptCount = 0`, clears the failure fields, and writes a
`MANUAL_REQUEUE` audit transition. Only `FAILED` is accepted; anything else returns
`409 NOT_REQUEUEABLE`, and an unknown id returns `404`. The audit trail therefore distinguishes
"failed by itself" from "an operator decided to try again".

**Abandoned claims.** A record found in `PROCESSING` past the visibility timeout is
treated as abandoned, regardless of how it got there. A sweep
(`TransactionRecoveryService.recoverStaleClaims`) finds `PROCESSING` rows whose `updated_at`
is older than `transactionguard.recovery.stale-claim-timeout` (default `5m`) and returns
them to `RETRY_PENDING` (`STALE_CLAIM_RECOVERED`). If the record has already consumed its
retry budget it becomes `FAILED` with `STALE_CLAIM_EXHAUSTED`, because a record that
repeatedly kills its worker must not be retried forever. In the normal path the claim,
effect and booking commit in one transaction, so a crash rolls the claim back; the sweep is
defence in depth for any `PROCESSING` row that is nevertheless left behind.

The sweep runs at startup, on a fixed-delay schedule, and on demand through
`POST /api/v1/processing/recover-stale`. Each candidate is recovered in its own transaction,
so one poison row cannot abort the sweep. Retrying is safe even if the original attempt had
already reached the settlement gateway, because the effect key makes the downstream call
idempotent.

---

## 7. Concurrency

### 7.1 Single instance

* The worker has a `cycleRunning` guard, so a slow cycle cannot overlap with the next
  scheduler tick.
* `claim` is a single atomic statement: several threads (or a manual
  `POST /processing/cycles` racing the scheduler) can poll, but only one wins a record.
* `StreamCursor` uses `findForUpdate` (pessimistic write lock) and `@Version`.
* `AccountBalance` uses `findForUpdate`, so concurrent debits on one account serialise.
* Ingestion of a stream is serialised by a per-stream `ReentrantLock`. This is not a
  throughput sacrifice for an ordered feed — the gate already allows only one record of a
  stream to be processed at a time — and it removes a self-inflicted race: two concurrent
  first-ever deliveries would both try to insert the same `stream_cursor` row and the loser
  would abort its whole ingestion transaction. Multi-instance safety is still provided by
  the unique keys plus the bounded conflict retry.

An earlier attempt used a `REQUIRES_NEW` cursor provisioner to make cursor creation
race-safe. It worked but starved the connection pool under 12 concurrent submitters
(each outer transaction held its connection while the inner one asked for another). The
in-process per-stream lock achieves the same result without nested transactions — a good
example of why "more transactions" is not automatically "more correct".

### 7.2 Multiple instances

The current design is safe but not optimal for horizontal scaling:

* Unique constraints prevent duplicates across instances.
* `claim` prevents double processing across instances.
* Cursor creation across instances can still collide; the ingestion retry loop absorbs it
  (10 attempts, 5 ms apart) and resolves to a duplicate outcome.
* The scheduler would run on every instance. That is wasteful but not incorrect (claims are
  atomic). Production would use ShedLock or a leader election to run the worker on one node,
  or partition streams across nodes.

### 7.3 Lock ordering

The processor always locks in the same order: record (via `UPDATE`), then account balance,
then cursor. Ingestion locks cursor then writes the record. Because the worker and the
ingester never hold both locks in opposite order for the same pair, deadlock is avoided.
For a multi-account transaction this would need a canonical ordering of account ids.

---

## 8. Observability

### 8.1 Logs

* Every line carries the request id (`[req=...]`) via MDC, set by `RequestTracingFilter`
  and echoed in the `X-Request-Id` response header.
* Account ids are masked (`ACC-***01`) by `SensitiveDataMasker`; amounts and transaction
  ids stay readable for reconciliation.
* Lifecycle events are logged once, at the transition, with the reason — not on every poll.

### 8.2 Metrics

`ProcessingMetrics` registers:

| Meter | Type | Meaning |
|---|---|---|
| `transactionguard.transactions.outcomes{outcome=accepted}` | counter | records admitted |
| `...{outcome=duplicate}` | counter | duplicate attempts suppressed |
| `...{outcome=rejected}` | counter | validation/business rejections |
| `...{outcome=failed}` | counter | retry budget exhausted |
| `...{outcome=processed}` | counter | effects applied |
| `...{outcome=retry_scheduled}` | counter | retries scheduled |
| `...{outcome=claim_skipped}` | counter | claim lost to another worker |
| `...{outcome=requeued}` | counter | operator requeues of `FAILED` records |
| `...{outcome=stale_claim_recovered}` | counter | abandoned claims returned to the worker |
| `transactionguard.transactions.current{status=...}` | gauge | current records per status |

Useful alerting signals: `failed` and `rejected` rate, `duplicate` rate (upstream resend
storm), `current{status=RETRY_PENDING}` (downstream degradation),
`current{status=PENDING_PREDECESSOR}` plus a non-empty gap report (missing delivery).

### 8.3 Operational endpoints

* `GET /processing/summary` — one call for the on-call dashboard: counts, duplicate/retry
  pressure, ledger size, net booked amount, balances, gaps. `countsByStatus.DUPLICATE` is
  always `0` because `DUPLICATE` is an API outcome, never a stored record state; use
  `duplicateAttempts` or the audit trail instead.
* `GET /processing/sequence-gaps` — the blocking diagnosis.
* `POST /processing/cycles` — manual drain; also proves the worker is a background
  component, not a request-scoped side effect.
* `POST /processing/recover-stale` — run the stale-claim sweep immediately.
* `POST /transactions/{id}/requeue` — operator recovery of a `FAILED` record.

### 8.4 Audit trail

`TransactionTransition` is append-only: `fromStatus`, `toStatus`, `reason` (stable code),
free-text detail, timestamp. It answers "who moved this and why" for every record, and is
returned in `GET /transactions/{id}`. Duplicate attempts are recorded there even though
they do not change the record state — that is where the value is.

### 8.5 API contract

* `GET /v3/api-docs` — generated OpenAPI 3 document; every controller operation has a
  summary and description.
* `/swagger-ui.html` — browsable Swagger UI for manual exploration.
* `postman/transaction-guard.postman_collection.json` — executable end-to-end scenario
  covering ingestion, processing, batch and error paths.
* `OpenApiIntegrationTest` — fails if the document is missing or a public path is renamed.

---

## 9. API design decisions

| Decision | Reasoning |
|---|---|
| `202` for accepted | processing is asynchronous; claiming `200` would imply completion |
| `200` for duplicate | a replay is a successful no-op, not an error; the body carries the original state |
| `400` / `409` / `422` | `400` = structurally unusable and not persisted; `409` = valid payload but the stream position is taken; `422` = understood but refused by a rule (stored as `REJECTED` when its identity is complete) |
| `207` for batch | partial success is the normal case; the body has per-record outcomes and counts |
| `202` for requeue | the record is only scheduled for retry; `200` would imply the retry already happened |
| `409` for requeue refusal | the record exists but is not in a recoverable state (`NOT_REQUEUEABLE`) |
| `recorded` flag | lets the caller distinguish "stored and will be processed" from "refused" |
| Audit in the detail response | the caller does not need a second endpoint to explain a state |
| `X-Request-Id` | end-to-end tracing without a full tracing stack |

---

## 10. Data model reference

```
transaction_record
  id BIGINT PK
  request_id            VARCHAR UNIQUE
  transaction_id        VARCHAR UNIQUE
  stream_id             VARCHAR
  sequence_number       BIGINT
  account_id            VARCHAR
  transaction_type      VARCHAR   (CREDIT, DEBIT, REVERSAL)
  amount                DECIMAL(19,2)
  currency              VARCHAR(3)
  event_time            TIMESTAMP
  status                VARCHAR
  attempt_count         INT
  next_attempt_at       TIMESTAMP
  failure_code          VARCHAR
  failure_message       VARCHAR
  effect_applied        BOOLEAN
  original_transaction_id VARCHAR
  received_at, updated_at TIMESTAMP
  version               BIGINT
  UNIQUE (stream_id, sequence_number)

transaction_transition     append-only audit
  id, transaction_id, from_status, to_status, reason, detail, occurred_at

stream_cursor
  stream_id PK, next_expected_sequence, updated_at, version

ledger_entry
  id, transaction_id UNIQUE, request_id, account_id, signed_amount, currency,
  balance_after, booked_at

account_balance
  account_id PK, currency, balance, updated_at, version
```

---

## 11. Trade-offs and alternatives

| Decision | Alternative | Why chosen / what it costs |
|---|---|---|
| Durable state machine in the DB | in-memory queue | survives restart, supports audit and reconciliation; costs latency |
| Strict per-stream order | process immediately, reorder later | matches the upstream contract; costs head-of-line blocking |
| Reject stale records | accept and mark duplicate | keeps the sequence invariant honest; a genuinely new record with a consumed slot is a data error the upstream must fix |
| Deterministic backoff | jittered backoff | reproducible tests/demos; production should add jitter |
| In-process per-stream lock | distributed lock | simplest correct thing for one instance; documented multi-instance path |
| Simulated gateway | real HTTP client | deterministic fault injection for tests; the interface is the seam |
| H2 file DB | PostgreSQL | zero setup for the reviewer; SQL is portable and migrations are listed in §13 |
| No Lombok | Lombok | explicit code, no annotation-processor surprises in an assessment |

---

## 12. Test strategy

| Requirement | How it is proven |
|---|---|
| duplicate request → one effect | `IdempotencyIntegrationTest.concurrentDuplicateSubmissionsAreIdempotent` (12 threads), `IngestionIntegrationTest.duplicateRequestIsSuppressed` |
| same transaction, new request id | `differentRequestIdSameTransactionIsOneEffect` |
| out-of-order delivery | `OrderingIntegrationTest.outOfOrderArrivalIsBuffered` |
| shuffled feed, same result | `OrderIndependenceTest` |
| stale sequence | `IngestionIntegrationTest.staleSequenceIsRejected` |
| invalid row does not block | `OrderingIntegrationTest`, `SampleScenarioIntegrationTest` |
| retry then success | `RetryIntegrationTest.transientFailureIsRetriedThenProcessed`, sample scenario |
| retry exhaustion | `retryBudgetExhaustionMarksFailed` |
| backoff respected | `RetryBackoffIntegrationTest` |
| failed record does not block stream | `failedRecordDoesNotBlockStream` |
| failed record is recoverable | `RecoveryIntegrationTest` (requeue, stale claim, budget exhaustion) |
| no manual trigger needed | `ScheduledWorkerIntegrationTest` (Awaitility, scheduler only) |
| API contract | `TransactionApiIntegrationTest` (21 cases), `OpenApiIntegrationTest` (2 cases) |
| validation rules | `TransactionRequestValidatorTest` (12 cases) |
| reversal rules | `ReversalIntegrationTest` (5 cases) |

The suite is 97 tests across 18 classes. Tests run against real Spring contexts and an
in-memory database. The scheduler test runs the real scheduled worker; the other
integration tests drive cycles synchronously so they stay deterministic. Nothing about the
concurrency or ordering behaviour is mocked — only the external settlement system, which is
exactly the boundary the design isolates.

---

## 13. Production hardening

Ordered by value:

1. **Database.** PostgreSQL; Flyway migrations instead of `ddl-auto`. Add an index on
   `(status, next_attempt_at)` for the claim query and partial indexes on `stream_id`.
2. **Distributed scheduling.** ShedLock (or a leader-election lease) so only one instance
   runs a cycle, and stream partitioning for horizontal throughput.
3. **Outbox / inbox.** If other systems must react to `PROCESSED`, write an outbox row in
   the same transaction instead of publishing from the processor.
4. **Real settlement client.** Timeouts, connection pooling, circuit breaker, bulkhead,
   and an idempotency key header to the downstream.
5. **Retry jitter** plus per-stream retry budgets to avoid synchronised retry storms.
6. **Security.** Authentication/authorisation on the ingestion API, request signing,
   rate limiting per upstream, TLS, secrets management, audit log shipping.
7. **Data lifecycle.** Partition `transaction_transition` by month, retention policy,
   archive terminal records, PII review of the stored payload.
8. **Observability.** OpenTelemetry traces across ingestion → gateway, Prometheus scraping,
   alerts on the signals in §8.2, a dashboard for gaps and retry pressure.
9. **Load testing** at the expected feed rate, with a documented throughput number per
   stream and per account.
10. **Recovery runbooks.** The requeue and stale-claim endpoints exist; production still
    needs authorisation, an operator UI or CLI, and alerting on `STALE_CLAIM_EXHAUSTED`.

---

## 14. End-to-end flows

Sections 2–8 explain each mechanism in isolation. This section follows a request across
the boundaries so the interaction between those mechanisms is explicit. Paths are
relative to `/api/v1`.

### 14.1 Accepted record: HTTP to ledger

1. `RequestTracingFilter` assigns or propagates `X-Request-Id`, puts it in the MDC and
   returns it in the response header.
2. `TransactionController` delegates to `TransactionFeedService.ingestOne`. The service
   takes the per-stream lock and opens **one transaction per record**, so a bad record in
   a batch cannot roll back its neighbours.
3. `TransactionIngester` runs duplicate detection (`request_id`, then `transaction_id`)
   before validation, then collects structural issues with `TransactionRequestValidator`.
   An untrackable payload stops here (`400`, nothing persisted).
4. `SequenceGate.admit` consults the stream cursor:
   * `sequence == next expected` → `RECEIVED`;
   * `sequence > next expected` → `PENDING_PREDECESSOR`;
   * an already used slot → `SEQUENCE_CONFLICT`/`STALE_SEQUENCE` (`409`).
5. The record, the initial `TransactionTransition` and the cursor decision commit
   together. The response (`202`) carries status, `duplicate`, `recorded`, validation
   issues, sequence and stream.
6. The scheduler (`TransactionProcessingWorker`, every 250 ms by default) calls
   `findClaimableIds` for `RECEIVED`/`RETRY_PENDING` rows whose `next_attempt_at` has
   elapsed, then runs the atomic claim (`UPDATE ... WHERE status IN (...)`). Only one
   worker wins; the claim increments `attempt_count` and sets `PROCESSING`.
7. `TransactionProcessor.process` runs one business transaction:
   defensive `effect_applied` check → signed amount (credit/debit/reversal rules) →
   pessimistic lock on the account balance → currency and funds checks → idempotent
   settlement gateway call keyed by `transaction_id` → balance update → unique ledger
   insert → record `PROCESSED`.
8. In the same transaction, `SequenceGate.advanceAndRelease` moves the cursor across
   consecutive terminal rows and promotes the first waiting `PENDING_PREDECESSOR` record
   to `RECEIVED`.
9. Reads then assemble the stored evidence: `GET /transactions/{id}` returns the record,
   its ledger entry and the ordered history; `/processing/summary` aggregates counts,
   ledger size, net booked amount, balances and gaps.

Every step appends a `TransactionTransition`, so the detail response explains the record
without consulting logs.

### 14.2 Duplicate delivery

A replay is recognised at the widest key first: a known `request_id` is a duplicate of
that submission, then a known `transaction_id` is a replay of the same business
transaction (even with a new delivery id). Detection runs before validation so a replay
of a previously stored rejected record is reported as a duplicate, not re-judged; an
untrackable original was never stored, so its replay is validated again.

The original row is **not** claimed again and no second effect is scheduled. The response
is `200` with `duplicate=true`, the original state in `message`, and a
`DUPLICATE_REQUEST`/`DUPLICATE_TRANSACTION` audit row. A payload that reuses one identity
but changes another (ambiguous request/transaction pairing, different payload for the same
transaction, different record on a used sequence) is a conflict, not a duplicate.

### 14.3 Out-of-order delivery

Sequence 3 may arrive before 2. The record is persisted as `PENDING_PREDECESSOR`, which
is visible and auditable but not claimable. When the missing predecessor reaches a
terminal state, `advanceAndRelease` moves the cursor and flips the waiting record to
`RECEIVED`, and the next cycle claims it. The `(stream_id, sequence_number)` unique
constraint keeps two different records from occupying the same slot.

If the predecessor never arrives, the record stays parked and
`GET /processing/sequence-gaps` reports the stream, its cursor and the missing sequence.
Every terminal state — `PROCESSED`, `REJECTED`, `FAILED` — consumes its slot, so one bad
row cannot block the feed forever.

### 14.4 Transient failure and retry

The settlement gateway can time out. A `TransientSettlementException` is caught inside the
processing transaction: the record is moved to `RETRY_PENDING` with `next_attempt_at` from
`RetryPolicy` (exponential, capped, deterministic) and `RETRY_SCHEDULED` is audited. No
ledger row or balance change is committed. The claim query honours that due time, so a
retry is not attempted early.

Retrying is safe because the gateway suppresses a second effect for the same effect key
and the ledger has a unique `transaction_id`. After `max-attempts`, the record becomes
`FAILED` with `RETRY_EXHAUSTED`; the cursor advances and the rest of the stream continues.

### 14.5 Terminal failure, operator requeue and stale claims

`FAILED` is terminal for the automatic pipeline. An operator fixes the cause and calls
`POST /transactions/{id}/requeue`: the record returns to `RETRY_PENDING` with a fresh retry
budget, `attempt_count = 0`, cleared failure fields and a `MANUAL_REQUEUE` audit row.
Anything other than `FAILED` returns `409 NOT_REQUEUEABLE`; an unknown id returns `404`.

A record found in `PROCESSING` past the visibility timeout is treated as abandoned,
regardless of how it got there. The startup sweep, the scheduled sweep and
`POST /processing/recover-stale` find claims older than
`transactionguard.recovery.stale-claim-timeout` (default `5m`). A claim with budget left
returns to `RETRY_PENDING` (`STALE_CLAIM_RECOVERED`); an exhausted one becomes `FAILED`
with `STALE_CLAIM_EXHAUSTED`. Both paths are audited, and the sweep runs per record so
one poison record cannot abort the others. In the normal path the claim and the booking
commit together, so a crash rolls the claim back; the sweep is defence in depth.

### 14.6 Batch and file ingestion

`POST /transactions/batch` and the startup/demo JSON sources feed the same per-record
pipeline. Each element gets its own transaction, duplicate check, validation and sequence
decision, and the batch response (`207`) reports `total`, `countsByStatus` and a result per
record. A malformed or invalid element is isolated; valid elements are still admitted.
This is the path used by the assessment sample: six deliveries for five business
transactions produce one duplicate, one rejection, one retry and four ledger entries.

### 14.7 Where each guarantee is enforced

| Guarantee | Mechanism |
|---|---|
| Exactly one effect per business transaction | atomic claim, idempotent gateway effect key, unique `ledger_entry.transaction_id` |
| Order within a stream | cursor + `(stream_id, sequence_number)` unique constraint + `PENDING_PREDECESSOR` buffering |
| No silent stuck stream | terminal states consume their slot, gap report, audit history |
| Crash safety | one DB transaction per effect; retry after rollback; stale-claim sweep |
| Recoverability | bounded retries, operator requeue, abandoned-claim recovery |
| Auditability | append-only transitions, request-id logs, masked account ids |
| Published contract | `/v3/api-docs`, Swagger UI, Postman collection, `OpenApiIntegrationTest` |
