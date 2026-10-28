package com.transactionguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable bookkeeping entry. The unique constraint on {@code transaction_id} is
 * the last line of defence against a double booking: even if two workers raced,
 * the database accepts one entry only.
 */
@Entity
@Table(
        name = "ledger_entry",
        uniqueConstraints = @UniqueConstraint(name = "uk_ledger_entry_transaction", columnNames = "transaction_id"))
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "signed_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal signedAmount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "balance_after", precision = 19, scale = 2, nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    protected LedgerEntry() {
        // for JPA
    }

    public LedgerEntry(String transactionId, String requestId, String accountId, BigDecimal signedAmount,
                       String currency, BigDecimal balanceAfter, Instant bookedAt) {
        this.transactionId = transactionId;
        this.requestId = requestId;
        this.accountId = accountId;
        this.signedAmount = signedAmount.setScale(2);
        this.currency = currency;
        this.balanceAfter = balanceAfter.setScale(2);
        this.bookedAt = bookedAt;
    }

    public Long getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getSignedAmount() {
        return signedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getBookedAt() {
        return bookedAt;
    }
}
