package com.transactionguard.processing;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static com.transactionguard.support.TransactionFixtures.credit;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same gate, different scope: sequences are independent per account, so account B
 * is not blocked by a hole in account A.
 */
@TestPropertySource(properties = "transactionguard.ordering.scope=PER_ACCOUNT")
class PerAccountOrderingIntegrationTest extends IntegrationTestSupport {

    @Test
    void accountsHaveIndependentStreams() {
        ingestOne(credit("REQ-A2", "TXN-A2", "ACC-A", "20.00", 2));
        ingestOne(credit("REQ-B1", "TXN-B1", "ACC-B", "50.00", 1));

        assertThat(recordOf("TXN-A2").getStatus()).isEqualTo(TransactionStatus.PENDING_PREDECESSOR);
        assertThat(recordOf("TXN-B1").getStatus()).isEqualTo(TransactionStatus.RECEIVED);

        drain();
        assertThat(recordOf("TXN-B1").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-A2").getStatus()).isEqualTo(TransactionStatus.PENDING_PREDECESSOR);

        ingestOne(credit("REQ-A1", "TXN-A1", "ACC-A", "10.00", 1));
        drain();

        assertThat(recordOf("TXN-A1").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-A2").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(balanceOf("ACC-A")).isEqualByComparingTo("30.00");
        assertThat(balanceOf("ACC-B")).isEqualByComparingTo("50.00");
    }

    @Test
    void gapReportIsScopedToTheBlockedAccount() {
        ingestOne(credit("REQ-B2", "TXN-B2", "ACC-B", "20.00", 2));

        assertThat(sequenceGate.currentGaps())
                .singleElement()
                .satisfies(gap -> {
                    assertThat(gap.streamId()).isEqualTo("ACC-B");
                    assertThat(gap.missingSequences()).containsExactly(1L);
                });
    }
}
