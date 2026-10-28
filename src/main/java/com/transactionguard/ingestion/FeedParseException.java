package com.transactionguard.ingestion;

/**
 * Raised when an input feed cannot be parsed at all. Individual malformed rows do
 * not throw this; they are isolated and reported as rejected submissions.
 */
public class FeedParseException extends RuntimeException {

    public FeedParseException(String message) {
        super(message);
    }

    public FeedParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
