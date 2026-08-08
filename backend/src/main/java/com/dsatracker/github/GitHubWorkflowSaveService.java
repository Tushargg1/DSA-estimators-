package com.dsatracker.github;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class GitHubWorkflowSaveService {
    private final GitHubWorkflowSaveRepository saves;

    public GitHubWorkflowSaveService(GitHubWorkflowSaveRepository saves) {
        this.saves = saves;
    }

    @Transactional
    public GitHubDtos.WorkflowSaveStatusResponse request(Long userId) {
        saves.queue(userId, UUID.randomUUID().toString());
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
        if (saves.claim(userId) == 0) {
            return new GitHubDtos.WorkflowSaveClaimResponse(false, null);
        }
        GitHubWorkflowSave save = saves.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Workflow save claim failed"));
        return new GitHubDtos.WorkflowSaveClaimResponse(true, save.getRequestToken());
    }

    @Transactional
    public GitHubDtos.WorkflowSaveStatusResponse complete(
            Long userId, GitHubDtos.WorkflowSaveCompleteRequest request) {
        String token = requireToken(request == null ? null : request.requestToken());
        if (saves.complete(userId, token) == 0) throw staleRequest();
        return status(userId);
    }

    @Transactional
    public GitHubDtos.WorkflowSaveStatusResponse fail(
            Long userId, GitHubDtos.WorkflowSaveFailureRequest request) {
        String token = requireToken(request == null ? null : request.requestToken());
        String error = sanitizeError(request == null ? null : request.error());
        if (saves.fail(userId, token, error) == 0) throw staleRequest();
        return status(userId);
    }

    private static GitHubDtos.WorkflowSaveStatusResponse response(GitHubWorkflowSave save) {
        return new GitHubDtos.WorkflowSaveStatusResponse(
                save.getStatus(), save.getRequestedAt(), save.getLastSavedAt(),
                save.getLastError());
    }

    private static String requireToken(String value) {
        if (value == null || value.isBlank() || value.length() > 36) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "requestToken is required");
        }
        return value;
    }

    private static String sanitizeError(String value) {
        if (value == null || value.isBlank()) return "Repository workflow failed";
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return sanitized.substring(0, Math.min(sanitized.length(), 500));
    }

    private static ResponseStatusException staleRequest() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Workflow save request is no longer active");
    }
}
