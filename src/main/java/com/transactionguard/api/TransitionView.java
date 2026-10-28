package com.transactionguard.api;

import com.transactionguard.domain.TransactionTransition;

import java.time.Instant;

public record TransitionView(String fromStatus, String toStatus, String reason, String detail, Instant occurredAt) {

    static TransitionView from(TransactionTransition transition) {
        return new TransitionView(transition.getFromStatus(), transition.getToStatus(), transition.getReason(),
                transition.getDetail(), transition.getOccurredAt());
    }
}
