package com.dsatracker.github;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.UUID;

@Service
public class GitHubWorkflowSaveService {
    private final GitHubWorkflowSaveRepository saves;
    private final GitHubProgressPushHistoryRepository histories;
    private final GitHubProgressScheduleService schedules;

    public GitHubWorkflowSaveService(GitHubWorkflowSaveRepository saves,
                                     GitHubProgressPushHistoryRepository histories,
                                     GitHubProgressScheduleService schedules) {
        this.saves = saves;
        this.histories = histories;
        this.schedules = schedules;
    }

    @Transactional
    public GitHubDtos.WorkflowSaveStatusResponse request(Long userId) {
        String token = UUID.randomUUID().toString();
        saves.queue(userId, token);
        histories.insertQueued(userId, token, GitHubProgressPushTrigger.MANUAL.name());
        return status(userId);
    }

    @Transactional(readOnly = true)
    public GitHubDtos.WorkflowSaveStatusResponse status(Long userId) {
        return saves.findById(userId).map(GitHubWorkflowSaveService::response)
                .orElseGet(() -> new GitHubDtos.WorkflowSaveStatusResponse(
                        null, null, null, null));
    }

    @Transactional
    public GitHubDtos.WorkflowSaveClaimResponse claim(Long userId) {
        GitHubDtos.WorkflowSaveClaimResponse pending = claimCurrent(userId);
        if (pending.claimed()) {
            // A manual run at a due instant also consumes that scheduled slot.
            schedules.consumeDue(userId);
            return pending;
        }
        if (!schedules.consumeDue(userId)) return notClaimed();

        String token = UUID.randomUUID().toString();
        saves.queue(userId, token);
        histories.insertQueued(userId, token, GitHubProgressPushTrigger.SCHEDULED.name());
        return claimCurrent(userId);
    }

    private GitHubDtos.WorkflowSaveClaimResponse claimCurrent(Long userId) {
        if (saves.claim(userId) == 0) return notClaimed();
        GitHubWorkflowSave save = saves.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Workflow save claim failed"));
        histories.markRunning(userId, save.getRequestToken());
        return new GitHubDtos.WorkflowSaveClaimResponse(true, save.getRequestToken());
    }

    private static GitHubDtos.WorkflowSaveClaimResponse notClaimed() {
        return new GitHubDtos.WorkflowSaveClaimResponse(false, null);
    }

    @Transactional
    public GitHubDtos.WorkflowSaveStatusResponse complete(
            Long userId, GitHubDtos.WorkflowSaveCompleteRequest request) {
        String token = requireToken(request == null ? null : request.requestToken());
        String commitSha = sanitizeCommitSha(request == null ? null : request.commitSha());
        String commitUrl = sanitizeCommitUrl(request == null ? null : request.commitUrl());
        Integer changedFiles = validateChangedFiles(
                request == null ? null : request.changedFiles());
        if (saves.complete(userId, token) == 0) throw staleRequest();
        histories.markSucceeded(userId, token, commitSha, commitUrl, changedFiles);
        return status(userId);
    }

    @Transactional
    public GitHubDtos.WorkflowSaveStatusResponse fail(
            Long userId, GitHubDtos.WorkflowSaveFailureRequest request) {
        String token = requireToken(request == null ? null : request.requestToken());
        String error = sanitizeError(request == null ? null : request.error());
        if (saves.fail(userId, token, error) == 0) throw staleRequest();
        histories.markFailed(userId, token, error);
        return status(userId);
    }

    private static GitHubDtos.WorkflowSaveStatusResponse response(GitHubWorkflowSave save) {
        return new GitHubDtos.WorkflowSaveStatusResponse(
                save.getStatus(), save.getRequestedAt(), save.getLastSavedAt(),
                save.getLastError());
    }

    private static String requireToken(String value) {
        if (value == null || value.isBlank() || value.length() > 36) {
            throw invalid("requestToken is required");
        }
        return value;
    }

    private static String sanitizeCommitSha(String value) {
        if (value == null || value.isBlank()) return null;
        String sanitized = value.trim().toLowerCase(Locale.ROOT);
        if (!sanitized.matches("[0-9a-f]{7,64}")) {
            throw invalid("commitSha must be a 7 to 64 character hexadecimal hash");
        }
        return sanitized;
    }

    private static String sanitizeCommitUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String sanitized = value.replace("\r", "").replace("\n", "").trim();
        if (sanitized.length() > 2048) throw invalid("commitUrl is too long");
        try {
            URI uri = new URI(sanitized);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw invalid("commitUrl must be an absolute HTTP(S) URL");
            }
        } catch (URISyntaxException ex) {
            throw invalid("commitUrl must be an absolute HTTP(S) URL");
        }
        return sanitized;
    }

    private static Integer validateChangedFiles(Integer value) {
        if (value != null && value < 0) throw invalid("changedFiles must not be negative");
        return value;
    }

    private static String sanitizeError(String value) {
        if (value == null || value.isBlank()) return "Repository workflow failed";
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return sanitized.substring(0, Math.min(sanitized.length(), 500));
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private static ResponseStatusException staleRequest() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Workflow save request is no longer active");
    }
}
