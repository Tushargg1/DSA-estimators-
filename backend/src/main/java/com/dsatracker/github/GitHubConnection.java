package com.dsatracker.github;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "github_connections")
public class GitHubConnection {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "installation_id")
    private Long installationId;
    @Column(name = "account_id")
    private Long accountId;
    @Column(name = "account_login", length = 255)
    private String accountLogin;
    @Column(name = "repository_id")
    private Long repositoryId;
    @Column(name = "repository_full_name", length = 255)
    private String repositoryFullName;
    @Column(name = "default_branch", length = 255)
    private String defaultBranch;
    @Column(name = "extension_token_hash", length = 64)
    private String extensionTokenHash;
    @Column(name = "connect_state_hash", length = 64)
    private String connectStateHash;
    @Column(name = "connect_state_expires_at")
    private Instant connectStateExpiresAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GitHubConnection() { }
    public GitHubConnection(Long userId) {
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public Long getUserId() { return userId; }
    public Long getInstallationId() { return installationId; }
    public Long getAccountId() { return accountId; }
    public String getAccountLogin() { return accountLogin; }
    public Long getRepositoryId() { return repositoryId; }
    public String getRepositoryFullName() { return repositoryFullName; }
    public String getDefaultBranch() { return defaultBranch; }
    public String getExtensionTokenHash() { return extensionTokenHash; }
    public String getConnectStateHash() { return connectStateHash; }
    public Instant getConnectStateExpiresAt() { return connectStateExpiresAt; }

    public void setConnectState(String hash, Instant expiresAt) {
        connectStateHash = hash;
        connectStateExpiresAt = expiresAt;
        touch();
    }
    public void consumeConnectState() {
        connectStateHash = null;
        connectStateExpiresAt = null;
        touch();
    }
    public void linkInstallation(Long id, Long linkedAccountId, String login) {
        installationId = id;
        accountId = linkedAccountId;
        accountLogin = login;
        repositoryId = null;
        repositoryFullName = null;
        defaultBranch = null;
        touch();
    }
    public void selectRepository(Long id, String fullName, String branch) {
        repositoryId = id;
        repositoryFullName = fullName;
        defaultBranch = branch;
        touch();
    }
    public void setExtensionTokenHash(String hash) { extensionTokenHash = hash; touch(); }
    private void touch() { updatedAt = Instant.now(); }
}
