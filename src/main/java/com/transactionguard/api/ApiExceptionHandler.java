package com.transactionguard.api;

import com.transactionguard.domain.IllegalStatusTransitionException;
import com.transactionguard.ingestion.FeedParseException;
import com.transactionguard.validation.RequestValidationException;
import com.transactionguard.validation.ValidationIssue;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Maps exceptions to a stable API contract. Unknown failures are logged with the
 * full stack trace but answered with a generic message, so internals never leak to
 * the caller.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ApiError> handleValidation(RequestValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request, ex.issues());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(FeedParseException.class)
    public ResponseEntity<ApiError> handleFeedParse(FeedParseException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "FEED_PARSE_ERROR", ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(IllegalStatusTransitionException.class)
    public ResponseEntity<ApiError> handleTransition(IllegalStatusTransitionException ex,
                                                     HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "ILLEGAL_STATUS_TRANSITION", ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException ex,
                                                   HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "The request conflicts with existing data", request, List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex,
                                                     HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_JSON",
                "Request body is missing or not valid JSON", request, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Invalid value for parameter '" + ex.getName() + "'", request,
                List.of(ValidationIssue.of(String.valueOf(ex.getName()), "INVALID_PARAMETER",
                        "Expected a valid value")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        List<ValidationIssue> issues = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> ValidationIssue.of(error.getField(), "INVALID_FIELD", error.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, issues);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Reference the server logs with the request id.",
                request, List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                           HttpServletRequest request, List<ValidationIssue> details) {
        Instant now = clock.instant();
        ApiError error = new ApiError(now, status.value(), code, message, request.getRequestURI(), details);
        return ResponseEntity.status(status).body(error);
    }
}
