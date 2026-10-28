package com.transactionguard.processing;

/**
 * A failure that is safe to retry (timeout, 503, connection reset, ...).
 */
public class TransientSettlementException extends RuntimeException {

    private final String code;

    public TransientSettlementException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
