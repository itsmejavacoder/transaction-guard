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

The reverse case is also handled: a malformed duplicate of a malformed original is still
reported as invalid, because nothing was ever accepted.

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
| after claim, before gateway | `PROCESSING` (stuck) | scheduled/startup stale-claim sweep resets claims older than the visibility timeout to `RETRY_PENDING`; see §6.5 |
| after gateway, before commit | `RETRY_PENDING` (claim rolled back) | retry calls the gateway again; duplicate effect suppressed |
| after commit, before audit write | same transaction | audit is written in the same transaction, so it commits or rolls back together |
| after commit, before cursor advance | record `PROCESSED`, cursor behind | next cycle sees a terminal record at the expected sequence and advances |

The important design decision is that **the external effect happens before the local
booking**, inside the claim transaction, and the gateway is idempotent. The alternative
(book first, settle after) would require compensating transactions.

### 4.5 Concurrent duplicate submissions

Twelve threads submitting the identical request are a realistic replay-storm test. Three
layers cooperate:

* ingestion of one stream is serialised in-process (see §8.1), which removes the
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

Nothing is written to `transaction_record`, so the slot and the cursor are untouched. The
refusal itself is still written to the audit trail (the recorder keys transitions by
transaction id, not by foreign key), which is what lets an operator answer "why was this
record not accepted?" without application logs.

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
| unexpected `RuntimeException` | bug | `ProcessingFailureHandler` marks `FAILED`, audit `UNEXPECTED_FAILURE`, stream advanced |

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

**Abandoned claims.** A worker that dies between `claim` and commit leaves a record in
`PROCESSING`. A sweep (`TransactionRecoveryService.recoverStaleClaims`) finds `PROCESSING`
rows whose `updated_at` is older than `transactionguard.recovery.stale-claim-timeout`
(default `5m`) and returns them to `RETRY_PENDING` (`STALE_CLAIM_RECOVERED`). If the record
has already consumed its retry budget it becomes `FAILED` with `STALE_CLAIM_EXHAUSTED`,
because a record that repeatedly kills its worker must not be retried forever.

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
| duplicate request → one effect | `IdempotencyIntegrationTest.concurrentDuplicateSubmissionsAreIdempotent` (12 threads), `...SameRequestIdIsDuplicate` |
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
| API contract | `TransactionApiIntegrationTest` (21 cases) |
| validation rules | `TransactionRequestValidatorTest` (12 cases) |
| reversal rules | `ReversalIntegrationTest` (5 cases) |

Tests run against real Spring contexts, a real (in-memory) database and the real
scheduler. Nothing about the concurrency or ordering behaviour is mocked — only the
external settlement system, which is exactly the boundary the design isolates.

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

## 14. AI usage

See [`AI_USAGE.md`](AI_USAGE.md) for the tools, the working method, what the AI produced,
and what was verified by execution rather than accepted on trust.
