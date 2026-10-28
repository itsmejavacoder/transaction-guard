package com.transactionguard.persistence;

import com.transactionguard.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Optional<LedgerEntry> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);

    @Query("select coalesce(sum(l.signedAmount), 0) from LedgerEntry l")
    BigDecimal sumBookedAmount();
}
