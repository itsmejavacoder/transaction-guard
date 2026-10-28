package com.transactionguard.api;

import com.transactionguard.domain.TransactionRequest;

import java.util.List;

/**
 * Payload for batch ingestion. Individual records are validated independently.
 */
public record TransactionBatchRequest(List<TransactionRequest> transactions) {

    public TransactionBatchRequest {
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
    }
}
