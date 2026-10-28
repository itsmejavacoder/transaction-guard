# Sample Run — Captured Input and Output

This document contains output captured from a real run of the finished build, not
expected values. Every JSON block below was copied from `curl` or `Invoke-RestMethod`
against the packaged jar (`target/transaction-guard-1.0.0.jar`).

* **Environment:** Windows 10, JDK 21 (Temurin), Maven 3.9.9, Spring Boot 3.5.16, H2 2.3.232.
* **Section 1:** the assessment sample scenario, one command (`run-demo.cmd`).
* **Section 2:** a 22-step API walkthrough of every status code and edge case.
* **Section 3:** the full test-suite evidence.
* **Section 4:** how to reproduce both.

Timestamps are UTC in JSON and local time (+05:30) in the console log.

---

## 1. The assessment scenario (demo profile)

### 1.1 Command

```cmd
cd transaction-guard
run-demo.cmd
```

`run-demo.cmd` deletes `data/`, packages the application and starts it with
`--spring.profiles.active=demo`. The demo profile:

* ingests `classpath:samples/sample-transactions.json` on startup,
* injects exactly one transient settlement failure for `TXN-2005`,
* sets the retry backoff to `0 ms` so the whole scenario completes in under a second,
* runs the background worker every 100 ms (no manual trigger is needed).

The same result can be produced without the script:

```cmd
rmdir /s /q data
mvnw.cmd -DskipTests package
java -jar target\transaction-guard-1.0.0.jar --spring.profiles.active=demo
```

### 1.2 Input

`src/main/resources/samples/sample-transactions.json` — six deliveries for five business
transactions, in the order given by the assessment:

| # | requestId | transactionId | Account | Type | Amount | Seq | Why it is here |
|---|---|---|---|---|---|---|---|
| 1 | `REQ-2` | `TXN-2002` | ACC-201 | DEBIT | 40.00 | 2 | arrives **before** its predecessor |
| 2 | `REQ-1` | `TXN-2001` | ACC-201 | CREDIT | 100.00 | 1 | unlocks the stream |
| 3 | `REQ-2-DUP` | `TXN-2002` | ACC-201 | DEBIT | 40.00 | 2 | replay with a new request id |
| 4 | `REQ-3` | `TXN-2003` | ACC-201 | CREDIT | 25.00 | 3 | normal record |
| 5 | `REQ-4` | `TXN-2004` | ACC-202 | DEBIT | **-10.00** | 4 | invalid amount |
| 6 | `REQ-5` | `TXN-2005` | ACC-203 | CREDIT | 500.00 | 5 | transient failure on attempt 1 |

### 1.3 Console log (verbatim excerpt)

```
2026-10-28 19:59:21.114 INFO  [main] [req=] c.t.ingestion.TransactionIngester - Accepted transaction transactionId=TXN-2002 account=ACC-***01 sequence=2 status=PENDING_PREDECESSOR
2026-10-28 19:59:21.134 INFO  [main] [req=] c.t.ingestion.TransactionIngester - Accepted transaction transactionId=TXN-2001 account=ACC-***01 sequence=1 status=RECEIVED
2026-10-28 19:59:21.143 INFO  [main] [req=] c.t.ingestion.TransactionIngester - Duplicate suppressed transactionId=TXN-2002 requestId=REQ-2-DUP reason=DUPLICATE_TRANSACTION
2026-10-28 19:59:21.154 INFO  [main] [req=] c.t.ingestion.TransactionIngester - Accepted transaction transactionId=TXN-2003 account=ACC-***01 sequence=3 status=PENDING_PREDECESSOR
2026-10-28 19:59:21.170 INFO  [main] [req=] c.t.ingestion.TransactionIngester - Rejected transaction transactionId=TXN-2004 code=VALIDATION_FAILED
2026-10-28 19:59:21.183 INFO  [main] [req=] c.t.ingestion.TransactionIngester - Accepted transaction transactionId=TXN-2005 account=ACC-***03 sequence=5 status=PENDING_PREDECESSOR
2026-10-28 19:59:21.185 INFO  [main] [req=] c.t.ingestion.TransactionFeedService - Ingested feed class path resource [samples/sample-transactions.json]: total=6 received=1 pending=3 duplicate=1 rejected=1
2026-10-28 19:59:21.233 INFO  [scheduling-1] [req=] c.t.processing.TransactionProcessor - Processed transactionId=TXN-2001 account=ACC-***01 amount=100.00 INR attempt=1
2026-10-28 19:59:21.250 INFO  [scheduling-1] [req=] c.t.processing.SequenceGate - Released transaction TXN-2002 (sequence 2) in stream GLOBAL
2026-10-28 19:59:21.265 INFO  [scheduling-1] [req=] c.t.processing.TransactionProcessor - Processed transactionId=TXN-2002 account=ACC-***01 amount=40.00 INR attempt=1
2026-10-28 19:59:21.275 INFO  [scheduling-1] [req=] c.t.processing.SequenceGate - Released transaction TXN-2003 (sequence 3) in stream GLOBAL
2026-10-28 19:59:21.296 INFO  [scheduling-1] [req=] c.t.processing.TransactionProcessor - Processed transactionId=TXN-2003 account=ACC-***01 amount=25.00 INR attempt=1
2026-10-28 19:59:21.310 INFO  [scheduling-1] [req=] c.t.processing.SequenceGate - Released transaction TXN-2005 (sequence 5) in stream GLOBAL
2026-10-28 19:59:21.332 WARN  [scheduling-1] [req=] c.t.processing.TransactionProcessor - Transient failure transactionId=TXN-2005 attempt=1/3 code=SIMULATED_GATEWAY_TIMEOUT nextAttemptAt=2026-10-28T14:29:21.319307Z
2026-10-28 19:59:21.344 INFO  [scheduling-1] [req=] c.t.processing.TransactionProcessor - Processed transactionId=TXN-2005 account=ACC-***03 amount=500.00 INR attempt=2
2026-10-28 19:59:21.357 INFO  [scheduling-1] [req=] c.t.p.TransactionProcessingWorker - Worker cycle finished claimed=5 processed=4 retry=1 rejected=0 failed=0 in 166 ms
```

Read it top to bottom and the whole requirement is visible:

1. `TXN-2002` is **parked**, not rejected, because sequence 1 is missing.
2. `TXN-2001` is accepted and claimed; when it finishes, the gate **releases** `TXN-2002`.
3. The replay of `TXN-2002` is suppressed with `DUPLICATE_TRANSACTION` and no debit.
4. `TXN-2004` is **rejected at ingestion** and never reaches the worker.
5. `TXN-2005` fails once (`SIMULATED_GATEWAY_TIMEOUT`), is retried, and succeeds on attempt 2.
6. One worker cycle claims five records: 4 processed, 1 retry, 0 failed.

Note that sequence 4 is consumed by the rejected `TXN-2004`, so `TXN-2005` (sequence 5)
becomes runnable without waiting forever.

### 1.4 Final summary

`GET /api/v1/processing/summary`:

```json
{
  "generatedAt": "2026-10-28T14:29:49.674647600Z",
  "totalTransactions": 5,
  "countsByStatus": {
    "PROCESSING": 0,
    "RECEIVED": 0,
    "PENDING_PREDECESSOR": 0,
    "RETRY_PENDING": 0,
    "PROCESSED": 4,
    "DUPLICATE": 0,
    "FAILED": 0,
    "REJECTED": 1
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

`GET /api/v1/processing/sequence-gaps`:

```json
[]
```

Arithmetic check:

| Account | Postings | Balance |
|---|---|---|
| ACC-201 | `+100.00` (TXN-2001) `−40.00` (TXN-2002) `+25.00` (TXN-2003) | **85.00** |
| ACC-203 | `+500.00` (TXN-2005, applied once despite the retry) | **500.00** |
| ACC-202 | none — `TXN-2004` was rejected | no balance row |

`585.00 = 85.00 + 500.00` is the net booked amount, and four ledger entries match four
`PROCESSED` records. The duplicate delivery produced **no** fifth entry.

### 1.5 Rejected record — `GET /api/v1/transactions/TXN-2004`

```json
{
  "requestId": "REQ-4",
  "transactionId": "TXN-2004",
  "accountId": "ACC-202",
  "streamId": "GLOBAL",
  "transactionType": "DEBIT",
  "amount": -10.00,
  "currency": "INR",
  "sequenceNumber": 4,
  "eventTime": "2026-10-27T10:00:04Z",
  "status": "REJECTED",
  "attemptCount": 0,
  "failureCode": "VALIDATION_FAILED",
  "failureMessage": "amount: amount must be a positive value; direction is expressed by transactionType",
  "effectApplied": false,
  "receivedAt": "2026-10-28T13:31:47.966985Z",
  "updatedAt": "2026-10-28T13:31:47.966985Z",
  "history": [
    {
      "toStatus": "REJECTED",
      "reason": "VALIDATION_FAILED",
      "detail": "amount: amount must be a positive value; direction is expressed by transactionType",
      "occurredAt": "2026-10-28T13:31:47.983457Z"
    }
  ]
}
```

`attemptCount = 0`, `effectApplied = false`, no ledger entry, no balance — the bad row
consumed its sequence and nothing else.

### 1.6 Retried record — `GET /api/v1/transactions/TXN-2005`

```json
{
  "requestId": "REQ-5",
  "transactionId": "TXN-2005",
  "accountId": "ACC-203",
  "streamId": "GLOBAL",
  "transactionType": "CREDIT",
  "amount": 500.00,
  "currency": "INR",
  "sequenceNumber": 5,
  "eventTime": "2026-10-27T10:00:05Z",
  "status": "PROCESSED",
  "attemptCount": 2,
  "effectApplied": true,
  "receivedAt": "2026-10-28T13:31:47.998836Z",
  "updatedAt": "2026-10-28T13:31:48.228038Z",
  "ledgerEntry": {
    "transactionId": "TXN-2005",
    "signedAmount": 500.00,
    "currency": "INR",
    "balanceAfter": 500.00,
    "bookedAt": "2026-10-28T13:31:48.228038Z"
  },
  "history": [
    { "fromStatus": "RECEIVED", "toStatus": "PENDING_PREDECESSOR", "reason": "INGESTED_PENDING_PREDECESSOR", "detail": "Waiting for sequence 1 before 5", "occurredAt": "2026-10-28T13:31:48.020033Z" },
    { "fromStatus": "PENDING_PREDECESSOR", "toStatus": "RECEIVED", "reason": "RELEASED_AFTER_PREDECESSOR", "detail": "Predecessors complete; sequence 5 released", "occurredAt": "2026-10-28T13:31:48.205836Z" },
    { "fromStatus": "RECEIVED", "toStatus": "PROCESSING", "reason": "CLAIMED_BY_WORKER", "detail": "Attempt 1", "occurredAt": "2026-10-28T13:31:48.210877Z" },
    { "fromStatus": "PROCESSING", "toStatus": "RETRY_PENDING", "reason": "RETRY_SCHEDULED", "detail": "Attempt 1 of 3 failed; next attempt at 2026-10-28T13:31:48.210877Z", "occurredAt": "2026-10-28T13:31:48.223399Z" },
    { "fromStatus": "RETRY_PENDING", "toStatus": "PROCESSING", "reason": "CLAIMED_BY_WORKER", "detail": "Attempt 2", "occurredAt": "2026-10-28T13:31:48.228038Z" },
    { "fromStatus": "PROCESSING", "toStatus": "PROCESSED", "reason": "EFFECT_APPLIED", "detail": "Effect booked and sequence advanced", "occurredAt": "2026-10-28T13:31:48.235893Z" }
  ]
}
```

Two attempts, **one** ledger entry, `effectApplied = true`. That is the idempotency claim
in one response.

---

## 2. API walkthrough (22 steps)

The demo profile proves the happy path. This walkthrough exercises every status code and
every refusal on a clean database with the scheduler **disabled**, so each step is
deterministic:

```cmd
java -jar target\transaction-guard-1.0.0.jar ^
  --server.port=8081 ^
  --transactionguard.worker.enabled=false ^
  --transactionguard.ingestion.startup-file=
```

Requests are in `docs/samples/`. `POST /api/v1/processing/cycles` plays the scheduler's
role; in the default profile the same work happens automatically every 250 ms.

### 2.1 Out-of-order arrival and the gap report

**Step 1 — sequence 2 arrives before sequence 1** (`out-of-order-seq2.json`)

```http
POST /api/v1/transactions
HTTP/1.1 202
```

```json
{"requestId":"REQ-1002","transactionId":"TXN-1002","status":"PENDING_PREDECESSOR","duplicate":false,"recorded":true,"message":"Accepted; waiting for earlier sequence in stream GLOBAL","validationIssues":[],"occurredAt":"2026-10-28T13:30:02.747719100Z","sequenceNumber":2,"streamId":"GLOBAL"}
```

**Step 2 — the hole is visible**

```http
GET /api/v1/processing/sequence-gaps
HTTP/1.1 200
```

```json
[{"streamId":"GLOBAL","nextExpectedSequence":1,"firstPendingSequence":2,"missingCount":1,"missingSequences":[1]}]
```

**Step 3 — exact replay of the parked record**

```http
POST /api/v1/transactions
HTTP/1.1 200
```

```json
{"requestId":"REQ-1002","transactionId":"TXN-1002","status":"DUPLICATE","duplicate":true,"recorded":true,"message":"Duplicate ignored; original state is PENDING_PREDECESSOR","validationIssues":[],"occurredAt":"2026-10-28T13:30:03.004746800Z","sequenceNumber":2,"streamId":"GLOBAL"}
```

The original state is echoed, so the caller can tell *parked* from *processed*.

**Step 4 — the predecessor arrives** (`out-of-order-seq1.json`)

```http
POST /api/v1/transactions
HTTP/1.1 202
```

```json
{"requestId":"REQ-1001","transactionId":"TXN-1001","status":"RECEIVED","duplicate":false,"recorded":true,"message":"Accepted for processing","validationIssues":[],"occurredAt":"2026-10-28T13:30:03.063746700Z","sequenceNumber":1,"streamId":"GLOBAL"}
```

**Step 5 — one worker cycle drains the stream**

```http
POST /api/v1/processing/cycles
HTTP/1.1 200
```

```json
{"startedAt":"2026-10-28T13:30:03.114748Z","finishedAt":"2026-10-28T13:30:03.229081400Z","alreadyRunning":false,"claimedCount":2,"processedCount":2,"retryScheduledCount":0,"rejectedCount":0,"failedCount":0,"skippedCount":0}
```

Both records were processed in one cycle: the gate released `TXN-1002` immediately after
`TXN-1001` committed.

**Step 6 — no gaps left**

```json
[]
```

### 2.2 A missing record versus a slow record

**Step 7 — sequence 4 arrives before 3** (`out-of-order-seq4.json`)

```json
{"requestId":"REQ-1004","transactionId":"TXN-1004","status":"PENDING_PREDECESSOR","duplicate":false,"recorded":true,"message":"Accepted; waiting for earlier sequence in stream GLOBAL","validationIssues":[],"occurredAt":"2026-10-28T13:30:03.291798300Z","sequenceNumber":4,"streamId":"GLOBAL"}
```

**Step 8 — sequence 3 was never delivered**

```json
[{"streamId":"GLOBAL","nextExpectedSequence":3,"firstPendingSequence":4,"missingCount":1,"missingSequences":[3]}]
```

**Step 9 — sequence 3 arrives** (`out-of-order-seq3.json`)

```json
{"requestId":"REQ-1003","transactionId":"TXN-1003","status":"RECEIVED","duplicate":false,"recorded":true,"message":"Accepted for processing","validationIssues":[],"occurredAt":"2026-10-28T13:30:03.359184800Z","sequenceNumber":3,"streamId":"GLOBAL"}
```

**Step 10 — the gap disappears before anything is processed**

```json
[]
```

This is the distinction that matters operationally: sequence 3 is *present but not terminal
yet*, so it is not a gap. The stream is not missing data; it is working.

**Step 11 — the cycle completes both**

```json
{"startedAt":"2026-10-28T13:30:03.412552700Z","finishedAt":"2026-10-28T13:30:03.458228200Z","alreadyRunning":false,"claimedCount":2,"processedCount":2,"retryScheduledCount":0,"rejectedCount":0,"failedCount":0,"skippedCount":0}
```

### 2.3 Duplicates and stream conflicts

**Step 12 — same business transaction, new delivery id** (`duplicate-transaction-replay.json`)

```http
POST /api/v1/transactions
HTTP/1.1 200
```

```json
{"requestId":"REQ-1003-AGAIN","transactionId":"TXN-1003","status":"DUPLICATE","duplicate":true,"recorded":true,"message":"Duplicate ignored; original state is PROCESSED","validationIssues":[],"occurredAt":"2026-10-28T13:30:03.484205900Z","sequenceNumber":3,"streamId":"GLOBAL"}
```

Caught by the **transaction id**, not the request id — the classic replay after a client
regenerates its delivery id.

**Step 13 — a *different* transaction tries to reuse sequence 3** (`sequence-conflict.json`)

```http
POST /api/v1/transactions
HTTP/1.1 409
```

```json
{"requestId":"REQ-1009","transactionId":"TXN-1009","status":"REJECTED","duplicate":false,"recorded":false,"message":"Rejected: Sequence 3 is already used in stream GLOBAL","validationIssues":[{"field":"sequenceNumber","code":"SEQUENCE_CONFLICT","message":"Sequence 3 is already used in stream GLOBAL"}],"occurredAt":"2026-10-28T13:30:03.527612200Z","sequenceNumber":3}
```

This is not a duplicate: it is an upstream defect. `recorded=false` and `409` say "fix the
payload; nothing was stored and nothing will be".

### 2.4 Invalid payloads

**Step 14 — negative amount** (`invalid-amount.json`)

```http
POST /api/v1/transactions
HTTP/1.1 422
```

```json
{"requestId":"REQ-1005","transactionId":"TXN-1005","status":"REJECTED","duplicate":false,"recorded":true,"message":"Rejected by validation","validationIssues":[{"field":"amount","code":"AMOUNT_NOT_POSITIVE","message":"amount must be a positive value; direction is expressed by transactionType"}],"occurredAt":"2026-10-28T13:30:03.571011700Z","sequenceNumber":5,"streamId":"GLOBAL"}
```

The record has a complete identity, so it is stored as `REJECTED` for audit — and it
consumes sequence 5 so the stream keeps moving.

**Step 15 — currency mismatch** (`currency-mismatch.json`)

```http
POST /api/v1/transactions
HTTP/1.1 422
```

```json
{"requestId":"REQ-1006","transactionId":"TXN-1006","status":"REJECTED","duplicate":false,"recorded":true,"message":"Rejected: currency mismatch","validationIssues":[{"field":"currency","code":"CURRENCY_MISMATCH","message":"Account is held in INR, request is in USD"}],"occurredAt":"2026-10-28T13:30:03.622615300Z","sequenceNumber":6,"streamId":"GLOBAL"}
```

A business rule that needs an existing balance: rejected without calling the settlement
gateway.

**Step 16 — missing request id** (`missing-request-id.json`)

```http
POST /api/v1/transactions
HTTP/1.1 400
```

```json
{"transactionId":"TXN-1010","status":"REJECTED","duplicate":false,"recorded":false,"message":"Rejected by validation","validationIssues":[{"field":"requestId","code":"REQUEST_ID_REQUIRED","message":"requestId is required"}],"occurredAt":"2026-10-28T13:30:03.668617700Z","sequenceNumber":0}
```

Untrackable: without a request id the service could not enforce idempotency, so the row is
refused before it is stored (`400`, `recorded=false`).

**Step 17 — sequence 2 long after the stream passed it** (`stale-sequence.json`)

```http
POST /api/v1/transactions
HTTP/1.1 409
```

```json
{"requestId":"REQ-1011","transactionId":"TXN-1011","status":"REJECTED","duplicate":false,"recorded":false,"message":"Sequence 2 was already passed (next expected 7)","validationIssues":[{"field":"sequenceNumber","code":"STALE_SEQUENCE","message":"Sequence 2 was already passed (next expected 7)"}],"occurredAt":"2026-10-28T13:30:03.707178700Z","sequenceNumber":2,"streamId":"GLOBAL"}
```

The stream watermark is 7, so accepting sequence 2 would rewrite history. Refused, not
stored.

### 2.5 Batch isolation

**Step 18 — one valid, one duplicate, one invalid** (`batch-mixed.json`)

```http
POST /api/v1/transactions/batch
HTTP/1.1 207
```

```json
{
  "total": 3,
  "countsByStatus": { "REJECTED": 1, "DUPLICATE": 1, "RECEIVED": 1 },
  "results": [
    {"requestId":"REQ-1007","transactionId":"TXN-1007","status":"RECEIVED","duplicate":false,"recorded":true,"message":"Accepted for processing","validationIssues":[],"occurredAt":"2026-10-28T13:30:03.747193100Z","sequenceNumber":7,"streamId":"GLOBAL"},
    {"requestId":"REQ-1002-AGAIN","transactionId":"TXN-1002","status":"DUPLICATE","duplicate":true,"recorded":true,"message":"Duplicate ignored; original state is PROCESSED","validationIssues":[],"occurredAt":"2026-10-28T13:30:03.759222800Z","sequenceNumber":2,"streamId":"GLOBAL"},
    {"requestId":"REQ-1008","transactionId":"TXN-1008","status":"REJECTED","duplicate":false,"recorded":true,"message":"Rejected by validation","validationIssues":[{"field":"amount","code":"AMOUNT_NOT_POSITIVE","message":"amount must be a positive value; direction is expressed by transactionType"}],"occurredAt":"2026-10-28T13:30:03.763027600Z","sequenceNumber":8,"streamId":"GLOBAL"}
  ]
}
```

The bad record did not abort the good one. Each result carries its own status, code and
message, so the upstream team can fix only what is broken.

**Step 19 — the cycle applies the valid record**

```json
{"startedAt":"2026-10-28T13:30:03.798150500Z","finishedAt":"2026-10-28T13:30:03.830714Z","alreadyRunning":false,"claimedCount":1,"processedCount":1,"retryScheduledCount":0,"rejectedCount":0,"failedCount":0,"skippedCount":0}
```

**Step 20 — summary**

```json
{"generatedAt":"2026-10-28T13:30:03.895059Z","totalTransactions":8,"countsByStatus":{"FAILED":0,"RETRY_PENDING":0,"PROCESSED":5,"RECEIVED":0,"PENDING_PREDECESSOR":0,"PROCESSING":0,"DUPLICATE":0,"REJECTED":3},"duplicateAttempts":3,"retryAttempts":0,"ledgerEntries":5,"netBookedAmount":100.00,"balances":[{"accountId":"ACC-900","currency":"INR","balance":100.00,"updatedAt":"2026-10-28T13:30:03.800149Z"}],"sequenceGaps":[]}
```

Eight records: five processed, three rejected, three duplicate attempts, five ledger
entries. `ACC-900`: `+100 −40 +10 +25 +5 = 100.00` — the two replays of `TXN-1002` added
nothing.

**Step 21 — audit trail of a replayed record**

`GET /api/v1/transactions/TXN-1002` (abridged to the history):

```json
{
  "transactionId": "TXN-1002",
  "status": "PROCESSED",
  "attemptCount": 1,
  "effectApplied": true,
  "ledgerEntry": { "transactionId": "TXN-1002", "signedAmount": -40.00, "currency": "INR", "balanceAfter": 60.00 },
  "history": [
    { "fromStatus": "RECEIVED", "toStatus": "PENDING_PREDECESSOR", "reason": "INGESTED_PENDING_PREDECESSOR", "detail": "Waiting for sequence 1 before 2" },
    { "fromStatus": "PENDING_PREDECESSOR", "toStatus": "DUPLICATE", "reason": "DUPLICATE_REQUEST", "detail": "Replay suppressed; original status is PENDING_PREDECESSOR" },
    { "fromStatus": "PENDING_PREDECESSOR", "toStatus": "RECEIVED", "reason": "RELEASED_AFTER_PREDECESSOR", "detail": "Predecessors complete; sequence 2 released" },
    { "fromStatus": "RECEIVED", "toStatus": "PROCESSING", "reason": "CLAIMED_BY_WORKER", "detail": "Attempt 1" },
    { "fromStatus": "PROCESSING", "toStatus": "PROCESSED", "reason": "EFFECT_APPLIED", "detail": "Effect booked and sequence advanced" },
    { "fromStatus": "PROCESSED", "toStatus": "DUPLICATE", "reason": "DUPLICATE_TRANSACTION", "detail": "Replay suppressed; original status is PROCESSED" }
  ]
}
```

Both duplicate attempts are in the audit trail even though neither changed the record's
state — that is how "how often is upstream resending?" becomes answerable.

**Step 22 — rejected records**

`GET /api/v1/transactions?status=REJECTED` returns `TXN-1005`, `TXN-1006` and `TXN-1008`,
each with `status: REJECTED`, `attemptCount: 0`, `effectApplied: false`, the failure code
and a one-row history. Example:

```json
{
  "requestId": "REQ-1006",
  "transactionId": "TXN-1006",
  "accountId": "ACC-900",
  "status": "REJECTED",
  "attemptCount": 0,
  "failureCode": "CURRENCY_MISMATCH",
  "failureMessage": "currency: Account is held in INR, request is in USD",
  "effectApplied": false
}
```

### 2.6 Status-code summary

| Step | Scenario | Code |
|---|---|---|
| 1 | accepted and parked for a predecessor | `202` |
| 2 | gap report | `200` |
| 3 | replay of a parked record | `200` |
| 4 | accepted and ready | `202` |
| 5 | worker cycle | `200` |
| 7 | accepted and parked | `202` |
| 9 | accepted and ready | `202` |
| 12 | duplicate business transaction | `200` |
| 13 | sequence already used by another record | `409` |
| 14 | invalid amount (trackable) | `422` |
| 15 | currency mismatch | `422` |
| 16 | untrackable payload | `400` |
| 17 | sequence already passed | `409` |
| 18 | mixed batch | `207` |

---

## 3. Test evidence

```
[INFO] Tests run: 97, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Per suite (from `target/surefire-reports`, run on 2026-10-28 after the OpenAPI contract test was added):

| # | Suite | Tests | Time | What it proves |
|---|---|---|---|---|
| 1 | `TransactionRequestValidatorTest` | 12 | 0.020 s | every structural rule, all issues collected, trackability |
| 2 | `TransactionLifecycleTest` | 5 | 0.044 s | legal/illegal transitions, operator-only `FAILED` requeue |
| 3 | `JsonFileTransactionSourceTest` | 5 | 0.152 s | feed parsing, malformed feeds, duplicate ids inside one file |
| 4 | `SensitiveDataMaskerTest` | 3 | 0.016 s | account ids are masked in logs |
| 5 | `IngestionIntegrationTest` | 10 | 4.088 s | accept, replay, ambiguous ids, invalid rows, stale sequences, audit |
| 6 | `OrderingIntegrationTest` | 7 | 1.001 s | buffering, release, gaps, rejection, failed record advances cursor |
| 7 | `PerAccountOrderingIntegrationTest` | 2 | 1.545 s | independent streams when `scope=PER_ACCOUNT` |
| 8 | `OrderIndependenceTest` | 1 | 2.625 s | shuffled feed → identical financial state |
| 9 | `IdempotencyIntegrationTest` | 4 | 0.700 s | 12 concurrent identical submissions → one effect |
| 10 | `RetryIntegrationTest` | 5 | 0.279 s | retry-then-success, exhaustion, failure does not block stream |
| 11 | `RetryBackoffIntegrationTest` | 1 | 1.161 s | a retry is not claimable before its backoff elapses |
| 12 | `RecoveryIntegrationTest` | 5 | 0.257 s | requeue, abandoned-claim recovery, stale budget exhaustion |
| 13 | `RetryPolicyTest` | 5 | 0.013 s | backoff maths, budget, microsecond rounding |
| 14 | `ReversalIntegrationTest` | 5 | 0.259 s | reversal rules (original, currency, amount, no reversal-of-reversal) |
| 15 | `SampleScenarioIntegrationTest` | 3 | 0.408 s | the assessment sample end to end |
| 16 | `ScheduledWorkerIntegrationTest` | 1 | 1.098 s | no manual trigger: the scheduler drains the feed |
| 17 | `TransactionApiIntegrationTest` | 21 | 4.017 s | status codes incl. `409`/`404`, batch `207`, filters, history, recovery |
| 18 | `OpenApiIntegrationTest` | 2 | 14.290 s | OpenAPI document and Swagger UI expose every public path |
| | **Total** | **97** | | |

The suite was executed repeatedly during development, including back-to-back runs with
identical results, to catch order-dependent flakiness. The timings above are from the final
run; wall-clock times vary with machine load and Spring context startup, so only the counts
and the zero failure/error total are assertions.

The concurrency suite is worth calling out explicitly. `IdempotencyIntegrationTest` fires
12 threads with the same payload and asserts:

* 1 accepted submission and 11 duplicates,
* 1 `transaction_record` row,
* 1 `ledger_entry` row,
* exactly 1 effect at the simulated gateway,
* 12 audit rows (one per attempt).

That is the strongest available evidence of the exactly-once claim, because it does not
trust any application-level check: it asserts the financial outcome.

---

## 4. Reproducing this document

```cmd
git clone https://github.com/itsmejavacoder/transaction-guard.git
cd transaction-guard

:: 1. full test suite (97 tests)
run-tests.cmd

:: 2. the assessment scenario (deletes data\, starts on port 8080)
run-demo.cmd

:: 3. while the demo is running
curl -s http://localhost:8080/api/v1/processing/summary
curl -s http://localhost:8080/api/v1/processing/sequence-gaps
curl -s http://localhost:8080/api/v1/transactions/TXN-2004
curl -s http://localhost:8080/api/v1/transactions/TXN-2005
```

On macOS/Linux, use `./run-tests.sh` and `./run-demo.sh`.

For the walkthrough in section 2, start a second instance without the scheduler:

```cmd
rmdir /s /q data
mvnw.cmd -DskipTests package
java -jar target\transaction-guard-1.0.0.jar --server.port=8081 --transactionguard.worker.enabled=false --transactionguard.ingestion.startup-file=
```

then replay `docs/samples/*.json` with `curl --data @file` and trigger the worker with
`curl -X POST http://localhost:8081/api/v1/processing/cycles` at steps 5, 11 and 19.

If the demo is restarted **without** deleting `data/`, the same feed is replayed as
duplicates and stale sequences — which is itself a correct demonstration of idempotency,
but not the clean scenario documented above. `run-demo.cmd` deletes the database first.
