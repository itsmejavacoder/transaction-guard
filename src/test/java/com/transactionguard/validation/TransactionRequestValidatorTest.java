package com.transactionguard.validation;

import com.transactionguard.domain.TransactionRequest;
import com.transactionguard.support.TransactionFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionRequestValidatorTest {

    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @Test
    @DisplayName("a well formed credit has no issues and is trackable")
    void acceptsValidRequest() {
        TransactionRequest request = TransactionFixtures.credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(validator.isTrackable(request)).isTrue();
    }

    @Test
    @DisplayName("missing identity fields make a record untrackable")
    void rejectsMissingRequestId() {
        TransactionRequest request = new TransactionRequest(null, "TXN-1", "ACC-1",
                com.transactionguard.domain.TransactionType.CREDIT, new BigDecimal("10.00"), "INR", 1L, null, null);

        assertThat(codes(validator.validate(request))).contains("REQUEST_ID_REQUIRED");
        assertThat(validator.isTrackable(request)).isFalse();
    }

    @Test
    void rejectsInvalidAccountIdCharacters() {
        TransactionRequest request = TransactionFixtures.credit("REQ-1", "TXN-1", "ACC 1", "10.00", 1);

        assertThat(codes(validator.validate(request))).contains("ACCOUNT_ID_INVALID");
        assertThat(validator.isTrackable(request)).isFalse();
    }

    @Test
    @DisplayName("direction is expressed by type, so a negative amount is invalid")
    void rejectsNegativeAmount() {
        TransactionRequest request = TransactionFixtures.debit("REQ-1", "TXN-1", "ACC-1", "-10.00", 1);

        assertThat(codes(validator.validate(request))).contains("AMOUNT_NOT_POSITIVE");
        assertThat(validator.isTrackable(request)).isTrue();
    }

    @Test
    void rejectsAmountWithTooManyDecimals() {
        TransactionRequest request = TransactionFixtures.credit("REQ-1", "TXN-1", "ACC-1", "10.001", 1);

        assertThat(codes(validator.validate(request))).contains("AMOUNT_SCALE_INVALID");
    }

    @Test
    void rejectsMissingAmount() {
        TransactionRequest request = TransactionFixtures.transaction("REQ-1", "TXN-1", "ACC-1",
                com.transactionguard.domain.TransactionType.CREDIT, null, "INR", 1L);

        assertThat(codes(validator.validate(request))).contains("AMOUNT_REQUIRED");
    }

    @Test
    void rejectsMissingOrMalformedCurrency() {
        TransactionRequest missing = TransactionFixtures.transaction("REQ-1", "TXN-1", "ACC-1",
                com.transactionguard.domain.TransactionType.CREDIT, "10.00", null, 1L);
        TransactionRequest lowercase = TransactionFixtures.transaction("REQ-2", "TXN-2", "ACC-1",
                com.transactionguard.domain.TransactionType.CREDIT, "10.00", "inr", 2L);

        assertThat(codes(validator.validate(missing))).contains("CURRENCY_REQUIRED");
        assertThat(codes(validator.validate(lowercase))).contains("CURRENCY_INVALID");
    }

    @Test
    void rejectsMissingOrNonPositiveSequence() {
        TransactionRequest missing = TransactionFixtures.transaction("REQ-1", "TXN-1", "ACC-1",
                com.transactionguard.domain.TransactionType.CREDIT, "10.00", "INR", null);
        TransactionRequest zero = TransactionFixtures.credit("REQ-2", "TXN-2", "ACC-1", "10.00", 0);

        assertThat(codes(validator.validate(missing))).contains("SEQUENCE_REQUIRED");
        assertThat(codes(validator.validate(zero))).contains("SEQUENCE_INVALID");
        assertThat(validator.isTrackable(missing)).isFalse();
    }

    @Test
    void rejectsMissingTransactionType() {
        TransactionRequest request = TransactionFixtures.transaction("REQ-1", "TXN-1", "ACC-1", null, "10.00", "INR", 1L);

        assertThat(codes(validator.validate(request))).contains("TYPE_REQUIRED");
    }

    @Test
    void reversalRequiresOriginalTransaction() {
        TransactionRequest request = TransactionFixtures.reversal("REQ-2", "TXN-2", "ACC-1", "100.00", 2, null);

        assertThat(codes(validator.validate(request))).contains("REVERSAL_ORIGINAL_REQUIRED");
    }

    @Test
    void reversalCannotReferenceItself() {
        TransactionRequest request = TransactionFixtures.reversal("REQ-2", "TXN-2", "ACC-1", "100.00", 2, "TXN-2");

        assertThat(codes(validator.validate(request))).contains("REVERSAL_ORIGINAL_SELF_REFERENCE");
    }

    @Test
    @DisplayName("all findings are reported in one pass")
    void collectsEveryIssue() {
        TransactionRequest request = new TransactionRequest(null, "TXN-1", "ACC-1", null,
                new BigDecimal("-1.00"), "inr", 0L, null, null);

        List<String> codes = codes(validator.validate(request));

        assertThat(codes).contains("REQUEST_ID_REQUIRED", "TYPE_REQUIRED", "AMOUNT_NOT_POSITIVE",
                "CURRENCY_INVALID", "SEQUENCE_INVALID");
    }

    private static List<String> codes(List<ValidationIssue> issues) {
        return issues.stream().map(ValidationIssue::code).toList();
    }
}
