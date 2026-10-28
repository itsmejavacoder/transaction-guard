package com.transactionguard.processing;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.transactionguard.support.TransactionFixtures.credit;
import static com.transactionguard.support.TransactionFixtures.reversal;
import static org.assertj.core.api.Assertions.assertThat;

class ReversalIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("a reversal compensates the original transaction")
    void reversalRestoresPreviousBalance() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        ingestOne(reversal("REQ-2", "TXN-2", "ACC-1", "100.00", 2, "TXN-1"));

        drain();

        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("0.00");
        assertThat(ledger.count()).isEqualTo(2);
        assertThat(ledger.findByTransactionId("TXN-2").orElseThrow().getSignedAmount())
                .isEqualByComparingTo("-100.00");
        assertThat(ledger.findByTransactionId("TXN-2").orElseThrow().getBalanceAfter())
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a reversal that arrives before its original is rejected (order is by sequence)")
    void reversalWithoutOriginalIsRejected() {
        ingestOne(reversal("REQ-1", "TXN-1", "ACC-1", "100.00", 1, "TXN-MISSING"));

        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(recordOf("TXN-1").getFailureCode()).isEqualTo("REVERSAL_ORIGINAL_NOT_FOUND");
        assertThat(ledger.count()).isZero();
    }

    @Test
    void reversalAmountMustMatchOriginal() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        ingestOne(reversal("REQ-2", "TXN-2", "ACC-1", "50.00", 2, "TXN-1"));

        drain();

        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(recordOf("TXN-2").getFailureCode()).isEqualTo("REVERSAL_AMOUNT_MISMATCH");
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("100.00");
    }

    @Test
    void reversalOfAReversalIsNotAllowed() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        ingestOne(reversal("REQ-2", "TXN-2", "ACC-1", "100.00", 2, "TXN-1"));
        ingestOne(reversal("REQ-3", "TXN-3", "ACC-1", "100.00", 3, "TXN-2"));

        drain();

        assertThat(recordOf("TXN-3").getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(recordOf("TXN-3").getFailureCode()).isEqualTo("REVERSAL_OF_REVERSAL_NOT_ALLOWED");
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("0.00");
    }

    @Test
    void reversalOfAPendingOriginalIsRejected() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 2));
        ingestOne(reversal("REQ-2", "TXN-2", "ACC-1", "100.00", 3, "TXN-1"));

        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.PENDING_PREDECESSOR);
        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.PENDING_PREDECESSOR);
    }
}
