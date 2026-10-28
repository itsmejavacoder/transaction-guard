package com.transactionguard.processing;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransitionReason;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.transactionguard.support.TransactionFixtures.credit;
import static com.transactionguard.support.TransactionFixtures.debit;
import static org.assertj.core.api.Assertions.assertThat;

class RetryIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("a transient failure is retried and the retry applies the effect once")
    void transientFailureIsRetriedThenProcessed() {
        gateway.failNext("TXN-1", 1);
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "500.00", 1));
        Long recordId = recordOf("TXN-1").getId();

        ProcessingOutcome firstAttempt = processor.process(recordId);

        assertThat(firstAttempt.status()).isEqualTo(TransactionStatus.RETRY_PENDING);
        assertThat(recordOf("TXN-1").getAttemptCount()).isEqualTo(1);
        assertThat(recordOf("TXN-1").getFailureCode()).isEqualTo("SIMULATED_GATEWAY_TIMEOUT");
        assertThat(recordOf("TXN-1").getNextAttemptAt()).isNotNull();
        assertThat(auditCount("TXN-1", TransitionReason.RETRY_SCHEDULED)).isEqualTo(1);
        assertThat(ledger.count()).isZero();

        ProcessingOutcome secondAttempt = processor.process(recordId);

        assertThat(secondAttempt.status()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(recordOf("TXN-1").getAttemptCount()).isEqualTo(2);
        assertThat(recordOf("TXN-1").getFailureCode()).isNull();
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("500.00");
        assertThat(gateway.attemptCount("TXN-1")).isEqualTo(2);
        assertThat(gateway.effectCount("TXN-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("after retry exhaustion the record is FAILED and no effect exists")
    void retryBudgetExhaustionMarksFailed() {
        gateway.failNext("TXN-1", 99);
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "500.00", 1));

        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(recordOf("TXN-1").getAttemptCount()).isEqualTo(3);
        assertThat(recordOf("TXN-1").getFailureCode()).isEqualTo("SIMULATED_GATEWAY_TIMEOUT");
        assertThat(auditCount("TXN-1", TransitionReason.RETRY_EXHAUSTED)).isEqualTo(1);
        assertThat(gateway.attemptCount("TXN-1")).isEqualTo(3);
        assertThat(gateway.effectCount("TXN-1")).isZero();
        assertThat(ledger.count()).isZero();
        assertThat(balances.count()).isZero();
    }

    @Test
    @DisplayName("the worker drives retries end to end without double booking")
    void workerDrivesRetryToCompletion() {
        gateway.failNext("TXN-1", 1);
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "500.00", 1));

        ProcessingCycleReport report = drain();

        assertThat(report.retryScheduledCount()).isEqualTo(1);
        assertThat(report.processedCount()).isEqualTo(1);
        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("500.00");
        assertThat(ledger.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a business rule failure is terminal and is not retried")
    void businessFailureIsNotRetried() {
        ingestOne(debit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(recordOf("TXN-1").getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(recordOf("TXN-1").getAttemptCount()).isEqualTo(1);
        assertThat(gateway.attemptCount("TXN-1")).isZero();
        assertThat(ledger.count()).isZero();
        assertThat(balances.count()).isZero();
        assertThat(auditCount("TXN-1", TransitionReason.BUSINESS_RULE_VIOLATION)).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed transaction does not block the rest of its stream")
    void failedRecordDoesNotBlockStream() {
        gateway.failNext("TXN-1", 99);
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "10.00", 1));
        ingestOne(credit("REQ-2", "TXN-2", "ACC-1", "20.00", 2));

        drain();

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(recordOf("TXN-2").getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(balanceOf("ACC-1")).isEqualByComparingTo("20.00");
    }
}
