package com.transactionguard.processing;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read model of an account balance for the processing report.
 */
public record AccountBalanceView(String accountId, String currency, BigDecimal balance, Instant updatedAt) {
}
