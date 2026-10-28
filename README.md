# TransactionGuard — Idempotent Background Transaction Processing Service

A Spring Boot service that ingests a transaction feed which may arrive **out of order**,
contain **duplicates**, and hit **transient failures**, and still guarantees that every
business transaction applies its financial effect **exactly once**.

This is the implementation of the Mphasis FDE L2 assessment *"Idempotent Background
Transaction Processing Service"* (see `docs/ASSESSMENT.md` for the requirement mapping).

---

## 1. What the service does

```
        POST /api/v1/transactions
                 │
                 ▼
   ┌─────────────────────────────┐
   │ Duplicate detection         │  request id → transaction id, before validation
   ├─────────────────────────────┤
   │ Validation                  │  structural rules, all findings collected
   ├─────────────────────────────┤
   │ Sequence gate (ordering)    │  RECEIVED / PENDING_PREDECESSOR / stale or slot conflict
   ├─────────────────────────────┤
   │ Persist + audit transition  │  one transaction per request
   └──────────────┬──────────────┘
                  │  (background, every 250 ms)
                  ▼
   ┌─────────────────────────────┐
   │ Worker cycle                │  atomically claims due records
   ├─────────────────────────────┤
   │ Processor                   │  idempotent gateway → ledger + balance → audit
   ├─────────────────────────────┤
   │ Retry / terminal handling   │  RETRY_PENDING with exponential backoff, or FAILED
   ├─────────────────────────────┤
   │ Recovery                    │  operator requeue + abandoned-claim sweep
   └─────────────────────────────┘
```

Guarantees:

| Property | Mechanism |
|---|---|
| No double effect for a replayed request | unique `request_id` + unique `transaction_id`, duplicate resolved **before** validation |
| No double effect under concurrent workers | atomic JPQL claim (`RECEIVED`/`RETRY_PENDING` → `PROCESSING`) |
| No double booking even if the effect succeeded and the DB commit failed | gateway is idempotent per effect key; ledger has a unique constraint on `transaction_id` |
| Ordering per stream | sequence cursor + `(stream_id, sequence_number)` unique constraint + `PENDING_PREDECESSOR` buffering |
| No silent stuck stream | sequence gap report + audit trail per state change |
| A dead worker does not lose a record | the claim and effect commit atomically; any `PROCESSING` claim older than the visibility timeout is recovered (`RETRY_PENDING`, or `FAILED` if the budget is spent) |
| A failed record is recoverable | audited operator requeue (`MANUAL_REQUEUE`) with a fresh retry budget |
| Transient failures are retried, bad data is not | failure classification, exponential backoff, bounded attempts |

---

## 2. Tech stack

| Item | Choice |
|---|---|
| Java | 21 (records, switch patterns, text blocks) |
| Framework | Spring Boot 3.5.16 (Web, Data JPA, Actuator, Scheduling) |
| Database | H2 (file mode by default, in-memory for tests) |
| Build | Maven (`pom.xml`, wrapper included) |
| Metrics | Micrometer counters + gauges via `/actuator` |
| API docs | springdoc OpenAPI 3 + Swagger UI, Postman collection |
| Tests | JUnit 5, Spring Boot Test, MockMvc, AssertJ, Awaitility |

No Lombok, no MapStruct, no XML: the domain is plain Java so the behaviour is explicit.

---

## 3. Project layout

```
transaction-guard/
├── pom.xml
├── mvnw / mvnw.cmd / .mvn/            Maven wrapper
├── run-demo.cmd / run-demo.sh         one-command demo
├── run-tests.cmd / run-tests.sh       full test suite
├── LICENSE                            MIT
├── .github/workflows/ci.yml           CI: build + tests on push
├── docs/
│   ├── DESIGN.md                     architecture, flows, idempotency, ordering, retry, hardening
│   ├── SAMPLE_RUN.md                 captured sample input/output
│   ├── ASSESSMENT.md                 requirement → implementation → test mapping
│   └── samples/                      request payloads used in the walkthrough
├── postman/                          importable Postman collection
└── src
    ├── main/java/com/transactionguard
    │   ├── api/                      REST controllers, read models, error handler, tracing filter
    │   ├── config/                   typed properties, Clock bean, OpenAPI definition
    │   ├── domain/                   aggregates, status machine, audit entity, exceptions
    │   ├── ingestion/                validators entry point, feed service, JSON source, dedupe
    │   ├── observability/            Micrometer counters/gauges, PII masker
    │   ├── persistence/              Spring Data repositories (claim query, gaps, counts)
    │   ├── processing/               sequence gate, processor, worker, recovery, retry policy, gateway
    │   └── validation/               structural validator + issues
    ├── main/resources
    │   ├── application.yml           production-ish defaults
    │   ├── application-demo.yml      sample feed + one injected transient failure
    │   └── samples/                  assessment feed
    └── test/java/com/transactionguard      unit + integration + edge case suites
```

---

## 4. Quick start

Prerequisites: JDK 21 (`java -version`), no local Maven needed (wrapper included).

```bash
git clone https://github.com/itsmejavacoder/transaction-guard.git
cd transaction-guard
```

On Windows:

```cmd
mvnw.cmd test
```

On macOS/Linux:

```bash
./mvnw test
```

Run the service:

```cmd
mvnw.cmd spring-boot:run
```

(or `./mvnw spring-boot:run`)

Then submit a transaction (default profile keeps the worker running every 250 ms):

```bash
curl -s -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{"requestId":"REQ-100","transactionId":"TXN-100","accountId":"ACC-1","transactionType":"CREDIT","amount":100.00,"currency":"INR","sequenceNumber":1,"eventTime":"2026-10-27T10:00:00Z"}'
```

On Windows `cmd`, replace `\` with `^` and use double quotes around the JSON body.

### Sample walkthrough

`docs/SAMPLE_RUN.md` contains the full walkthrough with real captured responses
(accepted, duplicate, invalid, out-of-order, batch, cycle report). The running service
also publishes a generated OpenAPI 3 document and Swagger UI; see
[OpenAPI and Postman](#openapi-and-postman) below.

---

## 5. The demo (assessment sample scenario)

The `demo` profile loads `classpath:samples/sample-transactions.json` on startup, injects
exactly one transient settlement failure for `TXN-2005`, and sets the retry backoff to
`0 ms` so the scenario finishes in about a second.

```cmd
run-demo.cmd
```

On macOS/Linux use `./run-demo.sh`. Or manually:

```cmd
rmdir /s /q data
mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

The feed is deliberately hostile:

| # | Transaction | What happens | Expected state |
|---|---|---|---|
| 1 | `TXN-2002` seq 2 | arrives **before** its predecessor | `PENDING_PREDECESSOR`, then released |
| 2 | `TXN-2001` seq 1 | arrives second, unlocks the stream | `PROCESSED` |
| 3 | `TXN-2002` again | replayed with a different request id | duplicate suppressed, **no second debit** |
| 4 | `TXN-2003` seq 3 | normal record | `PROCESSED` |
| 5 | `TXN-2004` amount `-10` | fails validation | `REJECTED` / `VALIDATION_FAILED`, sequence consumed |
| 6 | `TXN-2005` seq 5 | first settlement attempt fails transiently | `RETRY_PENDING` → `PROCESSED` on attempt 2 |

Captured final state (`GET /api/v1/processing/summary`):

```json
{
  "generatedAt": "2026-10-28T14:29:49.674647600Z",
  "totalTransactions": 5,
  "countsByStatus": {
    "PROCESSING": 0,
    "PENDING_PREDECESSOR": 0,
    "RECEIVED": 0,
    "REJECTED": 1,
    "PROCESSED": 4,
    "RETRY_PENDING": 0,
    "FAILED": 0,
    "DUPLICATE": 0
  },
  "duplicateAttempts": 1,
  "retryAttempts": 1,
  "ledgerEntries": 4,
  "netBookedAmount": 585.00,
  "balances": [
    { "accountId": "ACC-201", "currency": "INR", "balance": 85.00 },
    { "accountId": "ACC-203", "currency": "INR", "balance": 500.00 }
  ],
  "sequenceGaps": []
}
```

Why these numbers:

* `ACC-201`: `+100.00 (TXN-2001) − 40.00 (TXN-2002) + 25.00 (TXN-2003) = 85.00`
  The duplicate `TXN-2002` delivery did **not** debit a second time.
* `ACC-203`: `+500.00 (TXN-2005)` applied once despite the retry.
* `TXN-2004` is counted as a transaction but has no ledger entry and no balance row.
* One duplicate attempt and one retry attempt are visible in the audit trail.
* `countsByStatus.DUPLICATE` is always `0`: `DUPLICATE` is an API/audit outcome for a
  replayed request, never a stored record status. Use `duplicateAttempts` (or the audit
  trail of the original record) to see resend pressure.
* No sequence gaps: sequence 4 is occupied by the rejected record, so it is not `missing`.

The worker log shows the whole story without any manual trigger:

```
Accepted transaction transactionId=TXN-2002 ... sequence=2 status=PENDING_PREDECESSOR
Accepted transaction transactionId=TXN-2001 ... sequence=1 status=RECEIVED
Duplicate suppressed transactionId=TXN-2002 requestId=REQ-2-DUP reason=DUPLICATE_TRANSACTION
Rejected transaction transactionId=TXN-2004 code=VALIDATION_FAILED
Processed transactionId=TXN-2001 ...
Released transaction TXN-2002 (sequence 2) in stream GLOBAL
Processed transactionId=TXN-2002 ...
Processed transactionId=TXN-2003 ...
Released transaction TXN-2005 (sequence 5) in stream GLOBAL
Transient failure transactionId=TXN-2005 attempt=1/3 code=SIMULATED_GATEWAY_TIMEOUT
Processed transactionId=TXN-2005 ... attempt=2
Worker cycle finished claimed=5 processed=4 retry=1 rejected=0 failed=0 in 166 ms
```

---

## 6. API

Base path `/api/v1`.

### Ingestion

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/transactions` | submit one transaction |
| `POST` | `/transactions/batch` | submit many; one transaction per record, always `207` with per-record outcomes |

Single submit status codes:

| Code | Meaning |
|---|---|
| `202 Accepted` | persisted as `RECEIVED` or `PENDING_PREDECESSOR` |
| `200 OK` | duplicate replay, original state returned, no new effect |
| `400 Bad Request` | structurally invalid, **nothing persisted** |
| `409 Conflict` | payload is valid but the stream position is already used (`SEQUENCE_CONFLICT`) or already passed (`STALE_SEQUENCE`); nothing persisted |
| `422 Unprocessable Entity` | well-formed but rejected by a validation or business rule; for trackable rows the refusal is stored as `REJECTED` |
| `404 Not Found` | unknown transaction id on a read or requeue |
| `409 Conflict` on requeue | the record exists but is not `FAILED` (`NOT_REQUEUEABLE`) |

Duplicate responses also carry the original state so a caller can tell "already done"
from "in progress":

```json
{
  "requestId": "REQ-100",
  "transactionId": "TXN-2100",
  "status": "DUPLICATE",
  "duplicate": true,
  "recorded": true,
  "message": "Duplicate ignored; original state is PROCESSED",
  "validationIssues": [],
  "occurredAt": "2026-10-28T13:23:03.539778100Z",
  "sequenceNumber": 6,
  "streamId": "GLOBAL"
}
```

### Queries and operations

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/transactions/{transactionId}` | full record, ledger evidence and audit history |
| `GET` | `/transactions?status=&accountId=&limit=` | filtered list |
| `POST` | `/transactions/{transactionId}/requeue` | operator recovery: `FAILED` → `RETRY_PENDING` with a fresh retry budget (`202`) |
| `GET` | `/processing/summary` | status counts, duplicate/retry pressure, balances, gaps |
| `GET` | `/processing/sequence-gaps` | streams currently blocked by a hole |
| `POST` | `/processing/cycles` | run one worker cycle synchronously (demos, platforms without a scheduler) |
| `POST` | `/processing/recover-stale` | run the abandoned-claim sweep now and return `{generatedAt, recovered, failed}` |

Every request is tagged with an `X-Request-Id` (generated when absent) that is returned
in the response header and appears in every log line as `[req=...]`.

### OpenAPI and Postman

The running service publishes a generated OpenAPI 3 document and Swagger UI:

| Resource | URL |
|---|---|
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |

Every controller operation carries a summary and description, so the published contract
tracks the code. `OpenApiIntegrationTest` fails if the document disappears or a public
path is renamed.

A ready-to-run Postman collection is included at
[`postman/transaction-guard.postman_collection.json`](postman/transaction-guard.postman_collection.json).
Import it into Postman and run the folders in order:

1. **Ingestion** — single record, out-of-order record, missing predecessor, duplicate replay
2. **Process and inspect** — worker cycle, transaction detail, list, summary, gaps, stale-claim sweep
3. **Batch** — mixed valid/invalid batch, processing, rejected record
4. **Errors** — `400` structural validation, `409` sequence conflict, `409` requeue refusal, `404` unknown id

Collection variables control `baseUrl`. The scenario uses global sequences `1–6` and is
designed for a fresh database: delete `./data` before a full run so every request is
applied. Requests assert the status code and response fields documented above. The
collection was executed end to end with Newman against a local instance.

---

## 7. Configuration

All settings are bound to `TransactionGuardProperties` (`transactionguard.*`).

| Property | Default | Meaning |
|---|---|---|
| `transactionguard.worker.enabled` | `true` | start the scheduled worker |
| `transactionguard.worker.interval-ms` | `250` | delay between cycles |
| `transactionguard.worker.max-records-per-cycle` | `500` | safety cap per cycle |
| `transactionguard.worker.fetch-size` | `25` | claim batch size |
| `transactionguard.retry.max-attempts` | `3` | total attempts before `FAILED` |
| `transactionguard.retry.initial-backoff` | `200ms` | first retry delay |
| `transactionguard.retry.multiplier` | `2.0` | exponential factor |
| `transactionguard.retry.max-backoff` | `2s` | backoff ceiling |
| `transactionguard.ordering.enabled` | `true` | enforce sequence ordering |
| `transactionguard.ordering.scope` | `GLOBAL` | `GLOBAL` or `PER_ACCOUNT` |
| `transactionguard.ingestion.startup-file` | empty | optional feed replayed on startup |
| `transactionguard.recovery.enabled` | `true` | run the startup and scheduled stale-claim sweeps |
| `transactionguard.recovery.stale-claim-timeout` | `5m` | how long a record may stay `PROCESSING` before it is treated as abandoned |
| `transactionguard.recovery.interval-ms` | `60000` | delay between scheduled sweeps |
| `transactionguard.simulation.transient-failures` | empty | `transactionId: failureCount` map for the simulated gateway |

Environment variables work too (`TRANSACTIONGUARD_RETRY_MAX_ATTEMPTS=5`).

---

## 8. Tests

```cmd
run-tests.cmd
```

On macOS/Linux use `./run-tests.sh`.

97 tests, all green.

| Suite | Tests | Focus |
|---|---|---|
| `TransactionRequestValidatorTest` | 12 | every structural rule, multiple issues per request, trackability |
| `TransactionLifecycleTest` | 5 | legal/illegal state transitions, operator-only `FAILED` requeue |
| `JsonFileTransactionSourceTest` | 5 | parsing, malformed feeds, duplicate ids inside one file |
| `SensitiveDataMaskerTest` | 3 | PII masking in logs |
| `IngestionIntegrationTest` | 10 | accept, replay, ambiguous ids, invalid rows, stale sequences, audit |
| `OrderingIntegrationTest` | 7 | buffering, release, gaps, rejections, stale records |
| `PerAccountOrderingIntegrationTest` | 2 | independent per-account streams |
| `OrderIndependenceTest` | 1 | shuffled feed produces identical financial state |
| `IdempotencyIntegrationTest` | 4 | 12 concurrent identical submissions → one effect |
| `RetryIntegrationTest` | 5 | retry-then-success, exhaustion, failure does not block stream |
| `RetryBackoffIntegrationTest` | 1 | retry is not claimable before its backoff elapses |
| `RecoveryIntegrationTest` | 5 | requeue, stale-claim recovery, budget exhaustion |
| `ReversalIntegrationTest` | 5 | reversal rules (original, currency, amount, no reversal-of-reversal) |
| `SampleScenarioIntegrationTest` | 3 | the assessment sample end to end |
| `ScheduledWorkerIntegrationTest` | 1 | no manual trigger: the scheduler drains the feed |
| `RetryPolicyTest` | 5 | backoff maths, retry budget, microsecond due-time rounding |
| `TransactionApiIntegrationTest` | 21 | status codes incl. `409`/`404`, batch `207`, filters, errors, history, recovery |
| `OpenApiIntegrationTest` | 2 | OpenAPI document and Swagger UI expose every public path |

Tests use an in-memory H2 database per context, a simulated settlement gateway with
deterministic fault injection, and Awaitility for the scheduler test.

---

## 9. Design highlights

The full reasoning is in [`docs/DESIGN.md`](docs/DESIGN.md). The short version:

1. **Three-level protection.** A replay is caught by `request_id`, then by
   `transaction_id`; a different transaction trying to reuse a consumed
   `(stream_id, sequence_number)` is refused as a conflict instead of being applied.
   Duplicate detection runs *before* validation, so a replay of an already-accepted
   record is reported as a duplicate rather than as a validation error.
2. **Exactly-once effect.** The claim is a single atomic `UPDATE ... WHERE status IN
   (RECEIVED, RETRY_PENDING)`. The settlement gateway is idempotent per effect key, so a
   crash between the external call and the DB commit is safe to retry. The ledger has a
   unique constraint on `transaction_id` as a last line of defence.
3. **Ordering without deadlock.** Each stream keeps a cursor (`next_expected_sequence`).
   Early records are parked as `PENDING_PREDECESSOR`; terminal records advance the
   cursor and release the next one. Every terminal state (including `REJECTED` and
   `FAILED`) consumes its sequence, so one bad row cannot block the stream forever.
4. **Bounded retries with classification.** Transient settlement failures back off
   exponentially up to `FAILED`; business rule violations are terminal immediately and
   never reach the gateway.
5. **Recoverable failures.** A `FAILED` record can be requeued by an explicit, audited
   operator action, and a claim abandoned by a dead worker is returned to the retry
   pipeline after a visibility timeout.
6. **Operable by default.** Structured logs with request ids and masked account ids,
   audit rows for every state change, Micrometer counters/gauges, summary and gap
   endpoints.

---

## 10. Deliverables checklist

| Assessment deliverable | Location |
|---|---|
| Source code | `src/main/java/com/transactionguard` |
| Execution instructions | this file, section 4–5 |
| Sample input | `src/main/resources/samples/sample-transactions.json`, `docs/samples/` |
| Sample output | section 5, `docs/SAMPLE_RUN.md` |
| Test evidence | section 8, `docs/SAMPLE_RUN.md` |
| Design note | `docs/DESIGN.md` |
| API contract | `/v3/api-docs`, Swagger UI, `postman/transaction-guard.postman_collection.json` |
| Requirement traceability | `docs/ASSESSMENT.md` |
