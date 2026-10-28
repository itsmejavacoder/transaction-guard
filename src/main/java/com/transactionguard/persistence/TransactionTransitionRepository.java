package com.transactionguard.persistence;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.domain.TransactionTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionTransitionRepository extends JpaRepository<TransactionTransition, Long> {

    List<TransactionTransition> findByTransactionIdOrderByOccurredAtAscIdAsc(String transactionId);

    long countByToStatus(String toStatus);

    long countByReason(String reason);
}
