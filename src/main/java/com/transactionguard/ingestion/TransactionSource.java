package com.transactionguard.ingestion;

import com.transactionguard.domain.TransactionRequest;

import java.util.List;

/**
 * Replaceable input adapter (functional requirement FR-01). The core processor
 * knows nothing about files, HTTP or queues; it only receives validated requests
 * through {@link TransactionFeedService}.
 */
public interface TransactionSource {

    String description();

    List<TransactionRequest> readAll();
}
