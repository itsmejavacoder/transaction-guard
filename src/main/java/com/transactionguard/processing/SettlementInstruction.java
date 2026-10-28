package com.transactionguard.processing;

/**
 * Instruction handed to the (simulated) settlement gateway. The {@code effectKey} is
 * the downstream idempotency key: retrying the same key must never settle twice.
 */
public record SettlementInstruction(String effectKey,
                                    String requestId,
                                    String accountId,
                                    String currency,
                                    java.math.BigDecimal signedAmount) {
}
