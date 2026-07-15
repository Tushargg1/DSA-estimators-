package com.dsatracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * A single solved-problem record pulled from a platform.
 *
 * <p>Maps to the {@code submissions} table (design.md "Database Schema").
 *
 * <p>Design choices for this entity:
 * <ul>
 *   <li><b>platform</b> is stored as a {@link Platform} enum via
 *       {@code @Enumerated(EnumType.STRING)}, matching the {@code VARCHAR(20)}
 *       column and the documented {@code 'LEETCODE'|'CODEFORCES'|'GFG'} values.</li>
 *   <li><b>tags</b> maps the nullable MySQL {@code JSON} column to a
 *       {@code List<String>} using Hibernate 6 JSON support
 *       ({@code @JdbcTypeCode(SqlTypes.JSON)} + {@code columnDefinition="json"}).</li>
 *   <li>Timestamps are {@link Instant} (UTC).</li>
 * </ul>
 *
 * <p>The natural business key {@code (user_id, platform, problem_id, solved_at_utc)}
 * is enforced by a unique constraint so a poll re-run can never double-insert.
 */
@Entity
@Table(
        name = "submissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_submissions_natural_key",
                columnNames = {"user_id", "platform", "problem_id", "solved_at_utc"}
        )
)
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    private Platform platform;

    @Column(name = "problem_id", nullable = false, length = 150)
    private String problemId;

    @Column(name = "problem_name", nullable = false, length = 255)
    private String problemName;

    @Column(name = "difficulty", length = 20)
    private String difficulty;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "json")
    private List<String> tags;

    @Column(name = "solved_at_utc", nullable = false)
    private Instant solvedAtUtc;

    @Column(name = "is_first_attempt", nullable = false)
    private boolean isFirstAttempt;

    @Column(name = "counted_for_target", nullable = false)
    private boolean countedForTarget = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Platform getPlatform() {
        return platform;
    }

    public void setPlatform(Platform platform) {
        this.platform = platform;
    }

    public String getProblemId() {
        return problemId;
    }

    public void setProblemId(String problemId) {
        this.problemId = problemId;
    }

    public String getProblemName() {
        return problemName;
    }

    public void setProblemName(String problemName) {
        this.problemName = problemName;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public Instant getSolvedAtUtc() {
        return solvedAtUtc;
    }

    public void setSolvedAtUtc(Instant solvedAtUtc) {
        this.solvedAtUtc = solvedAtUtc;
    }

    public boolean isFirstAttempt() {
        return isFirstAttempt;
    }

    public void setFirstAttempt(boolean firstAttempt) {
        isFirstAttempt = firstAttempt;
    }

    public boolean isCountedForTarget() {
        return countedForTarget;
    }

    public void setCountedForTarget(boolean countedForTarget) {
        this.countedForTarget = countedForTarget;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
