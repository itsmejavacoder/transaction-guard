package com.transactionguard.ingestion;

import com.transactionguard.domain.TransactionRequest;

import java.util.List;

/**
 * In-memory source used by tests and by callers that already parsed their feed.
 */
public record InMemoryTransactionSource(List<TransactionRequest> requests) implements TransactionSource {

    public InMemoryTransactionSource {
        requests = requests == null ? List.of() : List.copyOf(requests);
    }

    @Override
    public String description() {
        return "in-memory feed (" + requests.size() + " records)";
    }

    @Override
    public List<TransactionRequest> readAll() {
        return requests;
    }
}
