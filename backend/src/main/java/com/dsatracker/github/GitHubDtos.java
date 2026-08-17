package com.dsatracker.github;

import java.time.Instant;
import java.util.List;

public final class GitHubDtos {
    private GitHubDtos() { }

    public record StatusResponse(boolean configured, boolean connected,
                                 boolean repositorySelected, boolean extensionTokenIssued,
                                 String accountLogin, String repositoryFullName,
                                 String defaultBranch) { }
    public record ConnectResponse(String installUrl, Instant expiresAt) { }
    public record CompleteRequest(Long installationId, String state) { }
    public record RepositoryResponse(Long id, String fullName, String defaultBranch,
                                     boolean selected) { }
    public record SelectRepositoryRequest(Long repositoryId) { }
    public record ExtensionTokenResponse(String token) { }
    public record CaptureResponse(Long captureId, boolean created, boolean updated,
                                  GitHubExportStatus exportStatus) { }
    public record CaptureExportResponse(Long captureId, GitHubPlatform platform,
                                        String problemId, String problemName,
                                        String problemUrl, String language,
                                        String source, String difficulty,
                                        List<String> tags, String patternSlug,
                                        Instant solvedAtUtc) { }
    public record CaptureCommand(GitHubPlatform platform, String problemId, String problemName,
                                 String problemUrl, String language, String source,
                                 String sourceHash, String difficulty, List<String> tags,
                                 Instant solvedAt) { }
    public record WorkflowSaveStatusResponse(GitHubWorkflowSaveStatus status,
                                             Instant requestedAt, Instant lastSavedAt,
                                             String lastError) { }
    public record WorkflowSaveClaimResponse(boolean claimed, String requestToken) { }
    public record WorkflowSaveCompleteRequest(String requestToken, String commitSha,
                                              String commitUrl, Integer changedFiles) {
        public WorkflowSaveCompleteRequest(String requestToken) {
            this(requestToken, null, null, null);
        }
    }
    public record WorkflowSaveFailureRequest(String requestToken, String error) { }
    public record ProgressScheduleUpdateRequest(Boolean enabled, String firstTime,
                                                String secondTime) { }
    public record ProgressScheduleResponse(boolean enabled, String firstTime,
                                           String secondTime, String timezone,
                                           Instant nextRunAt) { }
    public record ProgressPushResponse(Long id, GitHubProgressPushTrigger trigger,
                                       GitHubWorkflowSaveStatus status,
                                       Instant requestedAt, Instant startedAt,
                                       Instant completedAt, String commitSha,
                                       String commitUrl, Integer changedFiles,
                                       String lastError) { }
}
