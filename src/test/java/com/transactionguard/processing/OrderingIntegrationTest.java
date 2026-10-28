package com.transactionguard.processing;

import com.transactionguard.domain.TransactionRecord;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.ingestion.SubmissionOutcome;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.transactionguard.support.TransactionFixtures.credit;
import static com.transactionguard.support.TransactionFixtures.debit;
import static org.assertj.core.api.Assertions.assertThat;

class OrderingIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("a record that arrives before its predecessor is parked, then released")
    void outOfOrderArrivalIsBuffered() {
        SubmissionOutcome later = ingestOne(debit("REQ-2", "TXN-2", "ACC-1", "40.00", 2));
        assertThat(later.status()).isEqualTo(TransactionStatus.PENDING_PREDECESSOR);

        ProcessingCycleReport firstCycle = drain();
        assertThat(firstCycle.claimedCount()).isZero();
        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.PENDING_PREDECESSOR);

        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("processing follows sequence order even when arrival order is reversed")
    void processingOrderIsDeterministic() {
        ingestOne(credit("REQ-3", "TXN-3", "ACC-1", "25.00", 3));
        ingestOne(credit("REQ-2", "TXN-2", "ACC-1", "40.00", 2));
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        drain();

        List<String> processedOrder = ledger.findAll().stream()
                .sorted((left, right) -> left.getId().compareTo(right.getId()))
                .map(entry -> entry.getTransactionId())
                .toList();
        assertThat(processedOrder).containsExactly("TXN-1", "TXN-2", "TXN-3");
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("165.00");
    }

    @Test
    @DisplayName("the gap report explains what is blocking the stream")
    void reportsMissingSequences() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        ingestOne(credit("REQ-3", "TXN-3", "ACC-1", "25.00", 3));

        List<SequenceGap> gaps = sequenceGate.currentGaps();

        assertThat(gaps).hasSize(1);
        SequenceGap gap = gaps.get(0);
        assertThat(gap.streamId()).isEqualTo("GLOBAL");
        assertThat(gap.nextExpectedSequence()).isEqualTo(2);
        assertThat(gap.firstPendingSequence()).isEqualTo(3);
        assertThat(gap.missingCount()).isEqualTo(1);
        assertThat(gap.missingSequences()).containsExactly(2L);
    }

    @Test
    @DisplayName("filling the gap releases the whole waiting chain")
    void fillingGapReleasesChain() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        ingestOne(credit("REQ-3", "TXN-3", "ACC-1", "25.00", 3));
        ingestOne(credit("REQ-4", "TXN-4", "ACC-1", "10.00", 4));

        drain();
        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-3").getStatus()).isEqualTo(TransactionStatus.PENDING_PREDECESSOR);
        assertThat(recordOf("TXN-4").getStatus()).isEqualTo(TransactionStatus.PENDING_PREDECESSOR);
        assertThat(sequenceGate.currentGaps()).hasSize(1);

        ingestOne(credit("REQ-2", "TXN-2", "ACC-1", "40.00", 2));
        drain();

        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-3").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-4").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(sequenceGate.currentGaps()).isEmpty();
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("175.00");
    }

    @Test
    @DisplayName("a rejected record still advances the sequence so it cannot deadlock the stream")
    void rejectedRecordAdvancesSequence() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        SubmissionOutcome rejected = ingestOne(credit("REQ-2", "TXN-2", "ACC-1", "-20.00", 2));
        assertThat(rejected.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.REJECTED);

        ingestOne(credit("REQ-3", "TXN-3", "ACC-1", "10.00", 3));
        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-3").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("110.00");
    }

    @Test
    @DisplayName("the sequence cursor advances past terminal failures")
    void failedRecordAdvancesSequence() {
        gateway.failNext("TXN-1", 10);
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        ingestOne(credit("REQ-2", "TXN-2", "ACC-1", "25.00", 2));

        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("25.00");
        assertThat(sequenceGate.currentGaps()).isEmpty();
    }

    @Test
    @DisplayName("a duplicate delivery does not consume a sequence slot")
    void duplicateDoesNotConsumeSequence() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        ingestOne(credit("REQ-2", "TXN-2", "ACC-1", "10.00", 2));

        drain();

        TransactionRecord first = recordOf("TXN-1");
        assertThat(first.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("110.00");
    }
}
