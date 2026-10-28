package com.transactionguard.api;

/**
 * Raised when a referenced resource does not exist. Mapped to HTTP 404.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
