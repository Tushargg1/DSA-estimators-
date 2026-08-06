package com.dsatracker.github;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class GitHubConnectionService {
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private final GitHubConnectionRepository connections;
    private final GitHubExportJobRepository jobs;
    private final GitHubApiClient api;
    private final GitHubProperties properties;
    private final TransactionTemplate transactions;

    public GitHubConnectionService(GitHubConnectionRepository connections,
                                   GitHubExportJobRepository jobs,
                                   GitHubApiClient api, GitHubProperties properties,
                                   PlatformTransactionManager transactionManager) {
        this.connections = connections;
        this.jobs = jobs;
        this.api = api;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public GitHubDtos.StatusResponse status(Long userId) {
        GitHubConnection connection = connections.findById(userId).orElse(null);
        return new GitHubDtos.StatusResponse(properties.configured(),
                connection != null && connection.getInstallationId() != null,
                connection != null && connection.getRepositoryId() != null,
                connection != null && connection.getExtensionTokenHash() != null,
                connection == null ? null : connection.getAccountLogin(),
                connection == null ? null : connection.getRepositoryFullName(),
                connection == null ? null : connection.getDefaultBranch());
    }

    public GitHubDtos.ConnectResponse connect(Long userId) {
        requireConfigured();
        String state = GitHubCrypto.randomToken();
        Instant expiresAt = Instant.now().plus(STATE_TTL);
        transactions.executeWithoutResult(ignored -> {
            GitHubConnection connection = connections.findByUserIdForUpdate(userId)
                    .orElseGet(() -> new GitHubConnection(userId));
            connection.setConnectState(GitHubCrypto.sha256Hex(state), expiresAt);
            connections.save(connection);
        });
        String slug = URLEncoder.encode(properties.getApp().getSlug(), StandardCharsets.UTF_8);
        String url = "https://github.com/apps/" + slug + "/installations/new?state=" + state;
        return new GitHubDtos.ConnectResponse(url, expiresAt);
    }

    public GitHubDtos.StatusResponse complete(Long userId,
                                              GitHubDtos.CompleteRequest request) {
        requireConfigured();
        if (request == null || request.installationId() == null
                || request.installationId() <= 0 || blank(request.state())) {
            throw badState();
        }
        String suppliedState = request.state();
        Boolean valid = transactions.execute(ignored -> {
            GitHubConnection connection = connections.findByUserIdForUpdate(userId)
                    .orElse(null);
            return validState(connection, suppliedState);
        });
        if (!Boolean.TRUE.equals(valid)) throw badState();

        GitHubApiClient.GitHubInstallationInfo installation;
        try {
            installation = api.inspectInstallation(request.installationId());
        } catch (Exception ex) {
            throw githubUnavailable();
        }
        if (!request.installationId().equals(installation.id())) throw badState();
        transactions.executeWithoutResult(ignored -> {
            GitHubConnection connection = connections.findByUserIdForUpdate(userId)
                    .orElseThrow(GitHubConnectionService::badState);
            if (!validState(connection, suppliedState)) throw badState();
            connection.consumeConnectState();
            connection.linkInstallation(installation.id(), installation.accountId(),
                    installation.accountLogin());
            connections.save(connection);
        });
        return status(userId);
    }

    public List<GitHubDtos.RepositoryResponse> repositories(Long userId) {
        requireConfigured();
        GitHubConnection connection = connected(userId);
        try {
            return api.listRepositories(connection.getInstallationId()).stream()
                    .map(repository -> new GitHubDtos.RepositoryResponse(
                            repository.id(), repository.fullName(), repository.defaultBranch(),
                            repository.id().equals(connection.getRepositoryId())))
                    .toList();
        } catch (Exception ex) {
            throw githubUnavailable();
        }
    }

    public GitHubDtos.StatusResponse selectRepository(
            Long userId, GitHubDtos.SelectRepositoryRequest request) {
        requireConfigured();
        if (request == null || request.repositoryId() == null || request.repositoryId() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "repositoryId is required");
        }
        GitHubConnection before = connected(userId);
        GitHubApiClient.GitHubRepositoryInfo selected;
        try {
            selected = api.listRepositories(before.getInstallationId()).stream()
                    .filter(repository -> repository.id().equals(request.repositoryId()))
                    .findFirst().orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "Repository is not accessible to this installation"));
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw githubUnavailable();
        }

        GitHubApiClient.GitHubRepositoryInfo repository = selected;
        transactions.executeWithoutResult(ignored -> {
            GitHubConnection current = connections.findByUserIdForUpdate(userId)
                    .orElseThrow(GitHubConnectionService::notConnected);
            if (!before.getInstallationId().equals(current.getInstallationId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "GitHub connection changed; retry repository selection");
            }
            current.selectRepository(repository.id(), repository.fullName(),
                    repository.defaultBranch());
            connections.save(current);
            jobs.makePendingDueForUser(userId);
        });
        return status(userId);
    }

    public GitHubDtos.ExtensionTokenResponse issueExtensionToken(Long userId) {
        String token = GitHubCrypto.randomToken();
        transactions.executeWithoutResult(ignored -> {
            GitHubConnection connection = connections.findByUserIdForUpdate(userId)
                    .orElseGet(() -> new GitHubConnection(userId));
            connection.setExtensionTokenHash(GitHubCrypto.sha256Hex(token));
            connections.save(connection);
        });
        return new GitHubDtos.ExtensionTokenResponse(token);
    }

    public void delete(Long userId) { connections.deleteById(userId); }

    private GitHubConnection connected(Long userId) {
        GitHubConnection connection = connections.findById(userId)
                .orElseThrow(GitHubConnectionService::notConnected);
        if (connection.getInstallationId() == null) throw notConnected();
        return connection;
    }

    private static boolean validState(GitHubConnection connection, String suppliedState) {
        return connection != null
                && GitHubCrypto.constantTimeHexEquals(
                        connection.getConnectStateHash(), suppliedState)
                && connection.getConnectStateExpiresAt() != null
                && connection.getConnectStateExpiresAt().isAfter(Instant.now());
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub App integration is not configured");
        }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ResponseStatusException badState() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid or expired GitHub connection state");
    }
    private static ResponseStatusException notConnected() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "GitHub installation is not connected");
    }
    private static ResponseStatusException githubUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "GitHub API is temporarily unavailable");
    }
}
