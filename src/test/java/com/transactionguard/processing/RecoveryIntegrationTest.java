package com.transactionguard.processing;

import com.transactionguard.domain.RequeueNotAllowedException;
import com.transactionguard.domain.TransactionNotFoundException;
import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static com.transactionguard.support.TransactionFixtures.credit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the two recovery paths that make a failed record recoverable: explicit
 * operator requeue, and automatic recovery of an abandoned PROCESSING claim.
 */
class RecoveryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private TransactionRecoveryService recoveryService;

    @Test
    @DisplayName("a FAILED transaction can be requeued and then applies its effect once")
    void failedTransactionCanBeRequeuedAndProcessed() {
        gateway.failNext("TXN-1", 99);
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "500.00", 1));
        drain();
        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.FAILED);

        gateway.failNext("TXN-1", 0);
        recoveryService.requeue("TXN-1");

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.RETRY_PENDING);
        assertThat(recordOf("TXN-1").getAttemptCount()).isZero();
        assertThat(recordOf("TXN-1").getFailureCode()).isNull();
        assertThat(recordOf("TXN-1").getNextAttemptAt()).isNotNull();
        assertThat(auditCount("TXN-1", TransitionReason.MANUAL_REQUEUE)).isEqualTo(1);
        assertThat(metrics.requeuedCount()).isEqualTo(1);

        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-1").getAttemptCount()).isEqualTo(1);
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("500.00");
        assertThat(gateway.effectCount("TXN-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("only FAILED records are requeueable")
    void requeueOfNonFailedTransactionIsRejected() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        assertThatThrownBy(() -> recoveryService.requeue("TXN-1"))
                .isInstanceOf(RequeueNotAllowedException.class)
                .hasMessageContaining("RECEIVED");
        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.RECEIVED);
    }

    @Test
    @DisplayName("requeueing an unknown transaction is a not-found error")
    void requeueOfUnknownTransactionFails() {
        assertThatThrownBy(() -> recoveryService.requeue("DOES-NOT-EXIST"))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    @DisplayName("an abandoned PROCESSING claim is returned to the worker")
    void staleProcessingClaimIsRecovered() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        Long recordId = recordOf("TXN-1").getId();

        assertThat(claim(recordId, Instant.now().minus(Duration.ofMinutes(10)),
                TransactionStatus.RECEIVED)).isEqualTo(1);

        RecoveryReport report = recoveryService.recoverStaleClaims();

        assertThat(report.recovered()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.RETRY_PENDING);
        assertThat(recordOf("TXN-1").getAttemptCount()).isEqualTo(1);
        assertThat(auditCount("TXN-1", TransitionReason.STALE_CLAIM_RECOVERED)).isEqualTo(1);

        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("100.00");
        assertThat(ledger.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a stale claim that exhausted its retry budget becomes FAILED")
    void staleClaimAtRetryBudgetBecomesFailed() {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        Long recordId = recordOf("TXN-1").getId();
        Instant old = Instant.now().minus(Duration.ofMinutes(10));

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(claim(recordId, old,
                    TransactionStatus.RECEIVED, TransactionStatus.RETRY_PENDING)).isEqualTo(1);
            RecoveryReport report = recoveryService.recoverStaleClaims();
            if (attempt < 3) {
                assertThat(report.recovered()).isEqualTo(1);
                assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.RETRY_PENDING);
            } else {
                assertThat(report.failed()).isEqualTo(1);
            }
        }

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(recordOf("TXN-1").getFailureCode()).isEqualTo("STALE_CLAIM_EXHAUSTED");
        assertThat(recordOf("TXN-1").getAttemptCount()).isEqualTo(3);
        assertThat(auditCount("TXN-1", TransitionReason.RETRY_EXHAUSTED)).isEqualTo(1);
        assertThat(ledger.count()).isZero();
    }
}
