package com.dsatracker.github;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "github_workflow_saves")
public class GitHubWorkflowSave {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "request_token", nullable = false, length = 36)
    private String requestToken;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GitHubWorkflowSaveStatus status;
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "last_saved_at")
    private Instant lastSavedAt;
    @Column(name = "last_error", length = 500)
    private String lastError;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GitHubWorkflowSave() { }

    public Long getUserId() { return userId; }
    public String getRequestToken() { return requestToken; }
    public GitHubWorkflowSaveStatus getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getLastSavedAt() { return lastSavedAt; }
    public String getLastError() { return lastError; }
}
