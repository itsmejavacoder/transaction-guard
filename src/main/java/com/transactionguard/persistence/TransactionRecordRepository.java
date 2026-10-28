package com.transactionguard.persistence;

import com.transactionguard.domain.TransactionRecord;
import com.transactionguard.domain.TransactionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, Long> {

    Optional<TransactionRecord> findByRequestId(String requestId);

    Optional<TransactionRecord> findByTransactionId(String transactionId);

    boolean existsByAccountIdAndSequenceNumber(String accountId, long sequenceNumber);

    Optional<TransactionRecord> findByAccountIdAndSequenceNumber(String accountId, long sequenceNumber);

    Optional<TransactionRecord> findFirstByStreamIdAndSequenceNumber(String streamId, long sequenceNumber);

    Optional<TransactionRecord> findFirstByStreamIdAndStatusOrderBySequenceNumberAsc(String streamId,
                                                                                     TransactionStatus status);

    List<TransactionRecord> findByAccountIdOrderBySequenceNumberAsc(String accountId);

    List<TransactionRecord> findByStatusOrderBySequenceNumberAsc(TransactionStatus status);

    List<TransactionRecord> findAllByOrderByReceivedAtAscIdAsc();

    long countByStatus(TransactionStatus status);

    @Query("select distinct r.streamId from TransactionRecord r order by r.streamId")
    List<String> findDistinctStreamIds();

    @Query("select r.sequenceNumber from TransactionRecord r where r.streamId = :streamId")
    List<Long> findSequenceNumbersByStreamId(@Param("streamId") String streamId);

    /**
     * Atomic claim. Only the thread that flips the row from a claimable status to
     * PROCESSING may apply the business effect, so concurrent workers cannot
     * double-process a transaction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update TransactionRecord r
               set r.status = :processing,
                   r.attemptCount = r.attemptCount + 1,
                   r.updatedAt = :now
             where r.id = :id
               and r.status in :claimable
            """)
    int claim(@Param("id") Long id,
              @Param("processing") TransactionStatus processing,
              @Param("claimable") Collection<TransactionStatus> claimable,
              @Param("now") Instant now);

    @Query("""
            select r.id from TransactionRecord r
             where r.status in :claimable
               and (r.nextAttemptAt is null or r.nextAttemptAt <= :now)
             order by r.sequenceNumber asc, r.id asc
            """)
    List<Long> findClaimableIds(@Param("claimable") Collection<TransactionStatus> claimable,
                                @Param("now") Instant now,
                                Pageable pageable);

    @Query("""
            select new com.transactionguard.persistence.StatusCount(r.status, count(r))
              from TransactionRecord r
             group by r.status
            """)
    List<StatusCount> countGroupedByStatus();
}
