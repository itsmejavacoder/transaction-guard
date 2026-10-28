package com.transactionguard.domain;

/**
 * A terminal, non-retryable business rule violation (insufficient funds, currency
 * mismatch, unknown reversal target, ...). Carries a stable machine-readable code.
 */
public class BusinessRuleViolationException extends RuntimeException {

    private final String code;

    public BusinessRuleViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
