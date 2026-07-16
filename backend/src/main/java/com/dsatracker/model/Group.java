package com.dsatracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A friend group whose members share a leaderboard.
 *
 * <p>Maps to {@code tracker_groups}; the explicit name avoids SQL reserved-word
 * collisions and is shared by the Flyway migration and foreign keys.
 *
 * <p>The {@code created_by} foreign key to {@code users(id)} is modeled as a
 * plain {@code Long} column ({@link #createdBy}) rather than a JPA association,
 * keeping the entity a faithful, association-free mirror of the SQL columns.
 */
@Entity
@Table(name = "tracker_groups")
public class Group {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "invite_code", nullable = false, unique = true, length = 20)
    private String inviteCode;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "daily_target", nullable = false)
    private int dailyTarget = 3;

    @Column(name = "target_mode", nullable = false, length = 10)
    private String targetMode = "AUTO";

    @Column(name = "target_calculated_for_date")
    private java.time.LocalDate targetCalculatedForDate;

    @Column(name = "poll_version", nullable = false)
    private int pollVersion;

    @Column(name = "poll_active", nullable = false)
    private boolean pollActive;

    @Column(name = "poll_started_at")
    private Instant pollStartedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public int getDailyTarget() { return dailyTarget; }
    public void setDailyTarget(int dailyTarget) { this.dailyTarget = dailyTarget; }
    public String getTargetMode() { return targetMode; }
    public void setTargetMode(String targetMode) { this.targetMode = targetMode; }
    public java.time.LocalDate getTargetCalculatedForDate() { return targetCalculatedForDate; }
    public void setTargetCalculatedForDate(java.time.LocalDate date) { this.targetCalculatedForDate = date; }
    public int getPollVersion() { return pollVersion; }
    public void setPollVersion(int pollVersion) { this.pollVersion = pollVersion; }
    public boolean isPollActive() { return pollActive; }
    public void setPollActive(boolean pollActive) { this.pollActive = pollActive; }
    public Instant getPollStartedAt() { return pollStartedAt; }
    public void setPollStartedAt(Instant pollStartedAt) { this.pollStartedAt = pollStartedAt; }
}
