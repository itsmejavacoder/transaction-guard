package com.transactionguard.api;

import com.transactionguard.validation.ValidationIssue;

import java.time.Instant;
import java.util.List;

/**
 * Consistent error envelope. Never contains stack traces or internal class names.
 */
public record ApiError(Instant timestamp,
                       int status,
                       String code,
                       String message,
                       String path,
                       List<ValidationIssue> details) {

    public ApiError {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
