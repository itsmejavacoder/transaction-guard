package com.transactionguard.api;

import com.transactionguard.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryView(String transactionId,
                              BigDecimal signedAmount,
                              String currency,
                              BigDecimal balanceAfter,
                              Instant bookedAt) {

    static LedgerEntryView from(LedgerEntry entry) {
        return new LedgerEntryView(entry.getTransactionId(), entry.getSignedAmount(), entry.getCurrency(),
                entry.getBalanceAfter(), entry.getBookedAt());
    }
}
