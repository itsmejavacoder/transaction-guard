package com.transactionguard.persistence;

import com.transactionguard.domain.StreamCursor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StreamCursorRepository extends JpaRepository<StreamCursor, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StreamCursor c where c.streamId = :streamId")
    Optional<StreamCursor> findForUpdate(@Param("streamId") String streamId);
}
