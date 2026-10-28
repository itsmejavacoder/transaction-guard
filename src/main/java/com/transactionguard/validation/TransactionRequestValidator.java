package com.transactionguard.validation;

import com.transactionguard.domain.TransactionRequest;
import com.transactionguard.domain.TransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Structural validation of an inbound request. Deliberately separate from the
 * processor: malformed rows must be rejected before they touch balances, and a bad
 * row must never abort the rest of the batch.
 *
 * <p>All findings are collected (not fail-fast) so an upstream team gets the full
 * list in one response.</p>
 */
@Component
public class TransactionRequestValidator {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern ACCOUNT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final int MAX_INTEGER_DIGITS = 15;

    public List<ValidationIssue> validate(TransactionRequest request) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (request == null) {
            issues.add(ValidationIssue.of("request", "REQUEST_MISSING", "Request body is required"));
            return issues;
        }

        validateIdentifier(request.requestId(), "requestId", "REQUEST_ID", issues);
        validateIdentifier(request.transactionId(), "transactionId", "TRANSACTION_ID", issues);
        validateIdentifier(request.accountId(), "accountId", "ACCOUNT_ID", issues);

        if (request.transactionType() == null) {
            issues.add(ValidationIssue.of("transactionType", "TYPE_REQUIRED", "transactionType is required"));
        }

        validateAmount(request.amount(), issues);
        validateCurrency(request.currency(), issues);
        validateSequence(request.sequenceNumber(), issues);
        validateReversal(request, issues);

        return List.copyOf(issues);
    }

    /**
     * A rejected row is persisted (and therefore can advance the sequence gate) only
     * when its identity is complete. This keeps garbage from occupying a sequence.
     */
    public boolean isTrackable(TransactionRequest request) {
        if (request == null) {
            return false;
        }
        List<ValidationIssue> issues = validate(request);
        return issues.stream().noneMatch(issue -> switch (issue.code()) {
            case "REQUEST_ID_REQUIRED", "REQUEST_ID_INVALID",
                 "TRANSACTION_ID_REQUIRED", "TRANSACTION_ID_INVALID",
                 "ACCOUNT_ID_REQUIRED", "ACCOUNT_ID_INVALID",
                 "SEQUENCE_REQUIRED", "SEQUENCE_INVALID" -> true;
            default -> false;
        });
    }

    private void validateIdentifier(String value, String field, String prefix, List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(ValidationIssue.of(field, prefix + "_REQUIRED", field + " is required"));
        } else if (!IDENTIFIER.matcher(value).matches()) {
            issues.add(ValidationIssue.of(field, prefix + "_INVALID",
                    field + " must be 1-64 characters of letters, digits, '.', '_', ':' or '-'"));
        }
    }

    private void validateAmount(BigDecimal amount, List<ValidationIssue> issues) {
        if (amount == null) {
            issues.add(ValidationIssue.of("amount", "AMOUNT_REQUIRED", "amount is required"));
            return;
        }
        if (amount.signum() <= 0) {
            issues.add(ValidationIssue.of("amount", "AMOUNT_NOT_POSITIVE",
                    "amount must be a positive value; direction is expressed by transactionType"));
        }
        if (amount.scale() > 2) {
            issues.add(ValidationIssue.of("amount", "AMOUNT_SCALE_INVALID",
                    "amount must have at most 2 decimal places"));
        }
        if (amount.stripTrailingZeros().precision() - amount.stripTrailingZeros().scale() > MAX_INTEGER_DIGITS) {
            issues.add(ValidationIssue.of("amount", "AMOUNT_OUT_OF_RANGE",
                    "amount exceeds the supported range"));
        }
    }

    private void validateCurrency(String currency, List<ValidationIssue> issues) {
        if (currency == null || currency.isBlank()) {
            issues.add(ValidationIssue.of("currency", "CURRENCY_REQUIRED", "currency is required"));
        } else if (!CURRENCY.matcher(currency).matches()) {
            issues.add(ValidationIssue.of("currency", "CURRENCY_INVALID",
                    "currency must be a three-letter upper-case ISO-4217 code"));
        }
    }

    private void validateSequence(Long sequenceNumber, List<ValidationIssue> issues) {
        if (sequenceNumber == null) {
            issues.add(ValidationIssue.of("sequenceNumber", "SEQUENCE_REQUIRED",
                    "sequenceNumber is required for ordered processing"));
        } else if (sequenceNumber < 1) {
            issues.add(ValidationIssue.of("sequenceNumber", "SEQUENCE_INVALID",
                    "sequenceNumber must be 1 or greater"));
        }
    }

    private void validateReversal(TransactionRequest request, List<ValidationIssue> issues) {
        if (request.transactionType() != TransactionType.REVERSAL) {
            return;
        }
        String original = request.originalTransactionId();
        if (original == null || original.isBlank()) {
            issues.add(ValidationIssue.of("originalTransactionId", "REVERSAL_ORIGINAL_REQUIRED",
                    "originalTransactionId is required for a REVERSAL"));
            return;
        }
        if (!IDENTIFIER.matcher(original).matches()) {
            issues.add(ValidationIssue.of("originalTransactionId", "REVERSAL_ORIGINAL_INVALID",
                    "originalTransactionId must be 1-64 valid characters"));
        }
        if (original.equals(request.transactionId())) {
            issues.add(ValidationIssue.of("originalTransactionId", "REVERSAL_ORIGINAL_SELF_REFERENCE",
                    "a REVERSAL cannot reference itself"));
        }
    }
}
