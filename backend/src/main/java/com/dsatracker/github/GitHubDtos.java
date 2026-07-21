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
    public record CaptureResponse(Long captureId, boolean duplicate,
                                  GitHubExportStatus exportStatus) { }
    public record CaptureCommand(GitHubPlatform platform, String problemId, String problemName,
                                 String problemUrl, String language, String source,
                                 String sourceHash, String difficulty, List<String> tags,
                                 Instant solvedAt) { }
}
