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
@Table(name = "github_progress_push_history")
public class GitHubProgressPushHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "request_token", nullable = false, unique = true, length = 36)
    private String requestToken;
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private GitHubProgressPushTrigger trigger;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GitHubWorkflowSaveStatus status;
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "commit_sha", length = 64)
    private String commitSha;
    @Column(name = "commit_url", length = 2048)
    private String commitUrl;
    @Column(name = "changed_files")
    private Integer changedFiles;
    @Column(name = "last_error", length = 500)
    private String lastError;

    protected GitHubProgressPushHistory() { }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public GitHubProgressPushTrigger getTrigger() { return trigger; }
    public GitHubWorkflowSaveStatus getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getCommitSha() { return commitSha; }
    public String getCommitUrl() { return commitUrl; }
    public Integer getChangedFiles() { return changedFiles; }
    public String getLastError() { return lastError; }
}
