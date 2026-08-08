package com.dsatracker.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubWorkflowSaveServiceTest {
    private static final long USER_ID = 7L;
    @Mock private GitHubWorkflowSaveRepository saves;
    @InjectMocks private GitHubWorkflowSaveService service;

    @Test
    void requestQueuesSaveAndReturnsQueuedState() {
        org.mockito.Mockito.doReturn(Optional.of(saved(GitHubWorkflowSaveStatus.QUEUED, "queued", null)))
                .when(saves).findById(USER_ID);
        assertEquals(GitHubWorkflowSaveStatus.QUEUED, service.request(USER_ID).status());
        verify(saves).queue(org.mockito.ArgumentMatchers.eq(USER_ID), anyString());
    }

    @Test
    void claimReturnsActiveRequestToken() {
        when(saves.claim(USER_ID)).thenReturn(1);
        org.mockito.Mockito.doReturn(Optional.of(saved(GitHubWorkflowSaveStatus.RUNNING, "claim-token", null)))
                .when(saves).findById(USER_ID);
        var response = service.claim(USER_ID);
        assertTrue(response.claimed());
        assertEquals("claim-token", response.requestToken());
    }

    @Test
    void completeReturnsSuccessAndSavedTime() {
        Instant completedAt = Instant.parse("2026-08-08T12:00:00Z");
        when(saves.complete(USER_ID, "claim-token")).thenReturn(1);
        org.mockito.Mockito.doReturn(Optional.of(saved(GitHubWorkflowSaveStatus.SUCCEEDED, "claim-token", completedAt)))
                .when(saves).findById(USER_ID);
        var response = service.complete(USER_ID, new GitHubDtos.WorkflowSaveCompleteRequest("claim-token"));
        assertEquals(GitHubWorkflowSaveStatus.SUCCEEDED, response.status());
        assertEquals(completedAt, response.lastSavedAt());
    }

    @Test
    void failureSanitizesReportedError() {
        when(saves.fail(USER_ID, "claim-token", "bad thing")).thenReturn(1);
        org.mockito.Mockito.doReturn(Optional.of(saved(GitHubWorkflowSaveStatus.FAILED, "claim-token", null)))
                .when(saves).findById(USER_ID);
        var response = service.fail(USER_ID, new GitHubDtos.WorkflowSaveFailureRequest("claim-token", "bad\nthing"));
        assertEquals(GitHubWorkflowSaveStatus.FAILED, response.status());
        verify(saves).fail(USER_ID, "claim-token", "bad thing");
    }

    private static GitHubWorkflowSave saved(GitHubWorkflowSaveStatus status, String token, Instant lastSavedAt) {
        GitHubWorkflowSave save = mock(GitHubWorkflowSave.class);
        org.mockito.Mockito.lenient().when(save.getStatus()).thenReturn(status);
        org.mockito.Mockito.lenient().when(save.getRequestToken()).thenReturn(token);
        org.mockito.Mockito.lenient().when(save.getLastSavedAt()).thenReturn(lastSavedAt);
        return save;
    }
}
