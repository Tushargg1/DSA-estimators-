package com.dsatracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Last-known polling health per platform, surfaced on the internal status page.
 *
 * <p>Maps to the {@code poll_status} table whose primary key is the platform
 * itself ({@code VARCHAR(20)}). The {@link Platform} enum is used as the id via
 * {@code @Enumerated(EnumType.STRING)}, so the stored PK value is the platform
 * name (design.md, Requirement 9.2).
 */
@Entity
@Table(name = "poll_status")
public class PollStatus {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    private Platform platform;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_failure_reason", columnDefinition = "text")
    private String lastFailureReason;

    public Platform getPlatform() {
        return platform;
    }

    public void setPlatform(Platform platform) {
        this.platform = platform;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public void setLastFailureAt(Instant lastFailureAt) {
        this.lastFailureAt = lastFailureAt;
    }

    public String getLastFailureReason() {
        return lastFailureReason;
    }

    public void setLastFailureReason(String lastFailureReason) {
        this.lastFailureReason = lastFailureReason;
    }
}
