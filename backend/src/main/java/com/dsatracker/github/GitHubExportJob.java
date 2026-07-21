package com.dsatracker.github;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "github_export_jobs")
public class GitHubExportJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "capture_id", nullable = false, unique = true)
    private Long captureId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GitHubExportStatus status;
    @Column(name = "attempts", nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "claim_token", length = 36)
    private String claimToken;
    @Column(name = "last_error", length = 500)
    private String lastError;
    @Column(name = "exported_at")
    private Instant exportedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GitHubExportJob() { }

    public Long getId() { return id; }
    public Long getCaptureId() { return captureId; }
    public GitHubExportStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getClaimToken() { return claimToken; }

    public void claim(Instant until, String token) {
        status = GitHubExportStatus.PROCESSING;
        leaseUntil = until;
        claimToken = token;
        updatedAt = Instant.now();
    }
    public void defer(Instant next) {
        status = GitHubExportStatus.PENDING;
        nextAttemptAt = next;
        leaseUntil = null;
        claimToken = null;
        updatedAt = Instant.now();
    }
    public void succeed() {
        status = GitHubExportStatus.EXPORTED;
        exportedAt = Instant.now();
        leaseUntil = null;
        claimToken = null;
        lastError = null;
        updatedAt = exportedAt;
    }
    public void fail(String error, Instant retryAt) {
        attempts++;
        lastError = error;
        leaseUntil = null;
        claimToken = null;
        status = attempts >= 8 ? GitHubExportStatus.FAILED : GitHubExportStatus.PENDING;
        if (status == GitHubExportStatus.PENDING) nextAttemptAt = retryAt;
        updatedAt = Instant.now();
    }
}
