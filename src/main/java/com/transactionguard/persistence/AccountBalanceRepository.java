package com.transactionguard.persistence;

import com.transactionguard.domain.AccountBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, String> {

    /**
     * Pessimistic write lock for the short window in which an effect is booked.
     * Prevents lost updates when two accounts' streams run in parallel.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AccountBalance b where b.accountId = :accountId")
    Optional<AccountBalance> findForUpdate(@Param("accountId") String accountId);
}
