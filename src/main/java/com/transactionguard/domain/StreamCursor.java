package com.transactionguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Per-stream sequence watermark. A record can be claimed only when its
 * {@code sequenceNumber} equals {@code nextExpectedSequence}, which makes
 * processing order deterministic while still allowing arrival in any order.
 */
@Entity
@Table(name = "stream_cursor")
public class StreamCursor {

    @Id
    @Column(name = "stream_id", length = 64, nullable = false)
    private String streamId;

    @Column(name = "next_expected_sequence", nullable = false)
    private long nextExpectedSequence;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected StreamCursor() {
        // for JPA
    }

    public StreamCursor(String streamId, long firstExpectedSequence, Instant now) {
        this.streamId = streamId;
        this.nextExpectedSequence = firstExpectedSequence;
        this.updatedAt = now;
    }

    public long advance(Instant now) {
        this.nextExpectedSequence++;
        this.updatedAt = now;
        return nextExpectedSequence;
    }

    public String getStreamId() {
        return streamId;
    }

    public long getNextExpectedSequence() {
        return nextExpectedSequence;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
