package com.transactionguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Current balance of an account in its single settlement currency.
 *
 * <p>Rows are locked pessimistically while an effect is applied. The currency is
 * fixed by the first transaction of the account and enforced on later ones, which
 * avoids silently mixing currencies in one balance.</p>
 */
@Entity
@Table(name = "account_balance")
public class AccountBalance {

    @Id
    @Column(name = "account_id", length = 64, nullable = false)
    private String accountId;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "balance", precision = 19, scale = 2, nullable = false)
    private BigDecimal balance;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected AccountBalance() {
        // for JPA
    }

    public AccountBalance(String accountId, String currency, Instant now) {
        this.accountId = accountId;
        this.currency = currency;
        this.balance = BigDecimal.ZERO.setScale(2);
        this.updatedAt = now;
    }

    /**
     * Applies a signed monetary effect and returns the new balance.
     */
    public BigDecimal apply(BigDecimal signedAmount, Instant now) {
        this.balance = balance.add(signedAmount).setScale(2, RoundingMode.HALF_UP);
        this.updatedAt = now;
        return this.balance;
    }

    public boolean wouldGoNegative(BigDecimal signedAmount) {
        return balance.add(signedAmount).signum() < 0;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
