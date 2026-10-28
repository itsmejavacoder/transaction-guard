package com.transactionguard.validation;

/**
 * One validation finding. {@code field} is the request attribute, {@code code} is a
 * stable machine-readable identifier that clients and tests can assert on.
 */
public record ValidationIssue(String field, String code, String message) {

    public static ValidationIssue of(String field, String code, String message) {
        return new ValidationIssue(field, code, message);
    }
}
