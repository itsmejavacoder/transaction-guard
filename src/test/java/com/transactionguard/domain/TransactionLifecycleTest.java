package com.transactionguard.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionLifecycleTest {

    @Test
    void allowsTheHappyPath() {
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.RECEIVED, TransactionStatus.PROCESSING)).isTrue();
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.PROCESSING, TransactionStatus.PROCESSED)).isTrue();
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.PROCESSING, TransactionStatus.RETRY_PENDING)).isTrue();
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.RETRY_PENDING, TransactionStatus.PROCESSING)).isTrue();
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.RETRY_PENDING, TransactionStatus.FAILED)).isTrue();
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.PENDING_PREDECESSOR, TransactionStatus.RECEIVED)).isTrue();
    }

    @Test
    void rejectsIllegalJumps() {
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.RECEIVED, TransactionStatus.PROCESSED)).isFalse();
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.PROCESSING, TransactionStatus.RECEIVED)).isFalse();
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.PROCESSED, TransactionStatus.RETRY_PENDING)).isFalse();
    }

    @Test
    void terminalStatusesHaveNoSuccessors() {
        for (TransactionStatus status : new TransactionStatus[]{
                TransactionStatus.PROCESSED, TransactionStatus.REJECTED,
                TransactionStatus.DUPLICATE}) {
            assertThat(status.isTerminal()).isTrue();
            assertThat(TransactionLifecycle.successorsOf(status)).isEmpty();
        }
        assertThat(TransactionStatus.FAILED.isTerminal()).isTrue();
    }

    @Test
    void failedCanOnlyBeRecoveredByAnExplicitRequeue() {
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.FAILED, TransactionStatus.RETRY_PENDING)).isTrue();
        assertThat(TransactionLifecycle.successorsOf(TransactionStatus.FAILED))
                .containsExactly(TransactionStatus.RETRY_PENDING);
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.FAILED, TransactionStatus.PROCESSING)).isFalse();
        assertThat(TransactionLifecycle.isAllowed(TransactionStatus.FAILED, TransactionStatus.PROCESSED)).isFalse();
    }

    @Test
    void requireThrowsForIllegalTransition() {
        assertThatThrownBy(() -> TransactionLifecycle.require(TransactionStatus.PROCESSED,
                TransactionStatus.PROCESSING))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessageContaining("PROCESSED -> PROCESSING");
    }
}
