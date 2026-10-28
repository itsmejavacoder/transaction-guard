package com.transactionguard.validation;

import java.util.List;

/**
 * Raised when a request is structurally invalid and cannot be accepted at all.
 */
public class RequestValidationException extends RuntimeException {

    private final List<ValidationIssue> issues;

    public RequestValidationException(List<ValidationIssue> issues) {
        super("Request validation failed");
        this.issues = List.copyOf(issues);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
