package com.transactionguard.ingestion;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.transactionguard.support.TransactionFixtures.credit;
import static com.transactionguard.support.TransactionFixtures.debit;
import static com.transactionguard.support.TransactionFixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;

class IngestionIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("a valid request is persisted as RECEIVED with an audit entry")
    void acceptsValidRequest() {
        SubmissionOutcome outcome = ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        assertThat(outcome.status()).isEqualTo(TransactionStatus.RECEIVED);
        assertThat(outcome.recorded()).isTrue();
        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.streamId()).isEqualTo("GLOBAL");
        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.RECEIVED);
        assertThat(auditCount("TXN-1", TransitionReason.INGESTED_READY)).isEqualTo(1);
    }

    @Test
    @DisplayName("replaying the same requestId returns the original state and writes no new record")
    void duplicateRequestIsSuppressed() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        SubmissionOutcome replay = ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        assertThat(replay.status()).isEqualTo(TransactionStatus.DUPLICATE);
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.message()).contains("Duplicate ignored");
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(auditCount("TXN-1", TransitionReason.DUPLICATE_REQUEST)).isEqualTo(1);
    }

    @Test
    @DisplayName("a new delivery id for an already seen business transaction is a duplicate")
    void duplicateTransactionIsSuppressed() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        SubmissionOutcome replay = ingestOne(credit("REQ-1-RETRY", "TXN-1", "ACC-1", "100.00", 1));

        assertThat(replay.status()).isEqualTo(TransactionStatus.DUPLICATE);
        assertThat(replay.duplicate()).isTrue();
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(auditCount("TXN-1", TransitionReason.DUPLICATE_TRANSACTION)).isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate after processing does not touch the balance")
    void duplicateAfterProcessingKeepsSingleEffect() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        drain();

        SubmissionOutcome replay = ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        assertThat(replay.status()).isEqualTo(TransactionStatus.DUPLICATE);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("100.00");
        assertThat(ledger.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed but identifiable record is persisted as REJECTED and consumes its sequence")
    void invalidTrackedRecordDoesNotBlockTheStream() {
        SubmissionOutcome rejected = ingestOne(debit("REQ-1", "TXN-1", "ACC-1", "-5.00", 1));

        assertThat(rejected.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(rejected.recorded()).isTrue();
        assertThat(rejected.validationIssues()).extracting("code").contains("AMOUNT_NOT_POSITIVE");
        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.REJECTED);

        SubmissionOutcome next = ingestOne(credit("REQ-2", "TXN-2", "ACC-1", "10.00", 2));

        assertThat(next.status()).isEqualTo(TransactionStatus.RECEIVED);

        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("a record with missing identity fields is rejected without being persisted")
    void invalidUntrackedRecordIsNotPersisted() {
        SubmissionOutcome rejected = ingestOne(transaction("REQ-1", null, "ACC-1",
                com.transactionguard.domain.TransactionType.CREDIT, "10.00", "INR", 1L));

        assertThat(rejected.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(rejected.recorded()).isFalse();
        assertThat(rejected.validationIssues()).extracting("code").contains("TRANSACTION_ID_REQUIRED");
        assertThat(transactions.count()).isZero();
    }

    @Test
    @DisplayName("two different transactions on the same account sequence are rejected")
    void sequenceConflictIsRejected() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        SubmissionOutcome conflict = ingestOne(credit("REQ-2", "TXN-2", "ACC-1", "50.00", 1));

        assertThat(conflict.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(conflict.recorded()).isFalse();
        assertThat(conflict.validationIssues()).extracting("code").contains("SEQUENCE_CONFLICT");
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(gateway.totalEffects()).isZero();
    }

    @Test
    @DisplayName("an account keeps a single currency")
    void currencyMismatchIsRejected() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        drain();

        SubmissionOutcome mismatch = ingestOne(transaction("REQ-2", "TXN-2", "ACC-1",
                com.transactionguard.domain.TransactionType.CREDIT, "50.00", "USD", 2L));

        assertThat(mismatch.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(mismatch.validationIssues()).extracting("code").contains("CURRENCY_MISMATCH");
        assertThat(recordOf("TXN-2").getFailureCode()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a new transaction whose sequence was already passed is rejected as stale")
    void staleSequenceIsRejected() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        drain();

        SubmissionOutcome stale = ingestOne(credit("REQ-2", "TXN-2", "ACC-2", "50.00", 1));

        // The old sequence slot is already owned by TXN-1, so the stale delivery cannot
        // be persisted without breaking the (stream, sequence) invariant.
        assertThat(stale.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(stale.recorded()).isFalse();
        assertThat(stale.validationIssues()).extracting("code")
                .contains(TransitionReason.STALE_SEQUENCE);
        assertThat(transactions.findByTransactionId("TXN-2")).isEmpty();
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(gateway.totalEffects()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty batch is a no-op")
    void emptyBatchProducesNoOutcomes() {
        List<SubmissionOutcome> outcomes = feedService.ingestAll(List.of());

        assertThat(outcomes).isEmpty();
        assertThat(transactions.count()).isZero();
    }
}
