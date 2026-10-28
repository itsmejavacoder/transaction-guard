# AI-Assisted Development — Usage Note

The assessment explicitly allows AI tools. This note records **how** they were used, what
they produced, where they were wrong, and how every claim in this repository was verified.
It is written so a reviewer can reproduce the workflow and audit the result.

---

## 1. Tools and setup

| Purpose | Tool |
|---|---|
| Implementation partner | AI coding agent (DeepSeek V4.1 Flash) inside an agentic IDE harness with file, shell and Maven access |
| Requirements | Assessment `.docx`, converted to plain text and read directly |
| Build / test | Maven 3.9.9, JDK 21 (Temurin), Spring Boot 3.5.16 |
| Verification | `mvn test` (85 tests), real HTTP calls against the running jar, log inspection |

No external services, no paid APIs, no code generators beyond the AI agent. The project is
self-contained and runs offline once dependencies are cached.

---

## 2. Working method

The work was done in loops of **design → implement → run → read evidence → correct**, not as
one large generation followed by a review.

1. **Requirement extraction.** The problem statement was reduced to a checklist of
   functional requirements (`L2-FR-01..12`), NFRs (§6.1–6.7) and acceptance criteria
   (§9.7). Everything in `docs/ASSESSMENT.md` maps back to that checklist.
2. **Domain-first naming.** Before code, the ubiquitous language was fixed: `TransactionRecord`,
   `SequenceGate`, `AdmissionDecision`, `StreamCursor`, `RetryPolicy`, `ProcessingOutcome`.
   The assessment's generic names were replaced everywhere (for example, the assessment's
   "processed-request store" is realised as the record state machine plus the audit trail,
   and `requestId`/`transactionId` are kept distinct on purpose).
3. **Skeleton then behaviour.** Entities, repositories and properties first; then ingestion,
   then the sequence gate, then the worker, then the API. Each step compiled and ran.
4. **Tests as the specification.** Test classes were written per behaviour area (duplicate,
   ordering, retry, reversal, API), and the failing ones drove the design corrections
   listed in §4.
5. **Evidence over assertion.** The demo profile was executed and the resulting JSON and
   logs were captured into `docs/SAMPLE_RUN.md`; the README numbers come from that run, not
   from expectations.

---

## 3. Prompts that produced useful results

The effective prompts were the ones that constrained **invariants and failure modes**, not
the ones that asked for "a service".

Examples of the instruction style used:

* "Model the transaction lifecycle explicitly. Illegal transitions must throw, not be
  silently ignored."
* "The settlement call is external. Explain what happens if it succeeds and the database
  commit fails, and make the retry safe."
* "Design the out-of-order strategy as sequence-based buffering. Show the trade-off against
  timestamp ordering, including head-of-line blocking."
* "Write the tests a reviewer would try to break this with: replay with a new request id,
  concurrent identical submissions, an invalid row in the middle of a stream, retry
  exhaustion."
* "Do not assert the implementation; assert the business outcome (balances, ledger rows,
  gateway effects)."

Less useful prompts (and why): "add error handling" produced generic `try/catch` noise with
no classification; "make it production ready" produced a checklist of buzzwords rather than
code. Both were discarded in favour of specific failure matrices.

---

## 4. Where the AI was wrong, and how it was caught

This section is the honest part of the note. Each item was found by running the code and
reading the output, not by re-reading the generated code.

### 4.1 Nanosecond rounding made a retry invisible to the worker

**Symptom.** One integration test failed only in the full suite: a record that should have
been `FAILED` after three attempts stayed `RETRY_PENDING`. It passed when the test ran
alone.

**How it was found.** Temporary `DEBUG` logging of the worker's claimable ids showed:

```
fetch at 13:20:13.4089259Z -> [76]
transient failure attempt=1/3, nextAttemptAt=13:20:13.3929929Z
fetch at 13:20:13.4089259Z -> [76]
transient failure attempt=2/3, nextAttemptAt=13:20:13.4089259Z
fetch at 13:20:13.4089259Z -> []          <-- due time equals now, yet not claimable
```

The test configuration uses a `0 ms` backoff, so `nextAttemptAt == now`. H2 stores
timestamps at microsecond precision and **rounds** the nanosecond part, which pushed the
stored value a fraction of a microsecond into the future. Because Windows' clock updates in
~15 ms ticks, the next poll could observe exactly the same instant and skip the record —
then the cycle ended, leaving it `RETRY_PENDING`.

**Fix.** `RetryPolicy.nextAttemptAt` truncates down to microseconds, so "due now" is never
in the future. A regression test (`RetryPolicyTest.dueTimeIsNeverAfterTheRequestedInstant`)
locks it in.

**Lesson recorded in the design note.** Time semantics at persistence boundaries are a real
source of flakiness; the fix belongs in the domain policy, not in the test.

### 4.2 `REQUIRES_NEW` cursor provisioning starved the connection pool

**Symptom.** After a first attempt at making cursor creation race-safe, the 12-thread
concurrency test timed out after 30 s.

**Cause.** Each outer ingestion transaction held a Hikari connection while the
`REQUIRES_NEW` inner transaction requested a second one; 12 threads exhausted the pool and
blocked each other.

**Fix.** Replaced with a per-stream `ReentrantLock` around ingestion, which matches ordered
feed semantics and removes the self-inflicted race, while unique constraints still protect
multi-instance deployments. The dead end is documented in `docs/DESIGN.md` §7.1 because it
is a useful trade-off signal.

### 4.3 Stale sequences cannot be persisted

**Symptom.** The first design tried to store a stale delivery as a `REJECTED` record to keep
an audit trail. That collides with `(stream_id, sequence_number)`, which is owned by the
original record.

**Fix.** `AdmissionDecision` distinguishes "rejected and recorded" from "rejected and not
recorded". A stale delivery returns `recorded=false` and the API answers `422`.

### 4.4 Assertions that were stricter than the guarantee

One test asserted that all 11 concurrent duplicates carry `DUPLICATE_REQUEST`. In reality a
replay can be recognised by either the request-id or the transaction-id check depending on
commit visibility. The assertion was corrected to sum both reasons — the guarantee is "one
effect, eleven duplicate attempts", not "which lookup won".

That correction is a good example of the review rule applied throughout: **when a test and
the design disagree, decide which one is wrong** instead of loosening the assertion until it
passes.

---

## 5. Guardrails used throughout

* Every non-trivial change was compiled and the relevant tests executed immediately; the
  full suite was run at the end (twice, consecutively, to catch order-dependent flakiness).
* Temporary instrumentation was removed after diagnosis; no debug logging is left in
  production paths.
* No secrets, credentials or external endpoints exist in the repository. The demo database
  is H2 in a local folder.
* Determinism was preferred over cleverness: fixed clock injection, deterministic backoff,
  deterministic fault injection, in-memory test database.
* Public behaviour is asserted through the domain and the API, not through private fields.

---

## 6. What AI did *not* decide

For transparency, the choices that required judgement and are defended in the design note:

* the ordering strategy (sequence-based Option A) and its head-of-line trade-off;
* rejecting untrackable rows instead of persisting them;
* the exact duplicate-detection order (business id before validation);
* the retry classification (transient vs business-terminal);
* the per-stream serialisation trade-off for multi-instance deployments;
* the production-hardening priority list.

AI accelerated the implementation, the test matrix and the log-driven diagnosis. The
architecture decisions above are the ones a reviewer should probe in the interview.

---

## 7. Reproducing the verification

```cmd
cd D:\mydev\assesment
mvnw.cmd test                                                                  :: 85 tests
rmdir /s /q data
mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"                     :: sample scenario
curl -s http://localhost:8080/api/v1/processing/summary
curl -s http://localhost:8080/api/v1/processing/sequence-gaps
curl -s http://localhost:8080/api/v1/transactions/TXN-2005
```

The captured outputs are in [`SAMPLE_RUN.md`](SAMPLE_RUN.md); the requirement mapping is in
[`ASSESSMENT.md`](ASSESSMENT.md).
