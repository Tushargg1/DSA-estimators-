package com.dsatracker.github;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GitHubProgressHistoryService {
    private final GitHubProgressPushHistoryRepository histories;

    public GitHubProgressHistoryService(GitHubProgressPushHistoryRepository histories) {
        this.histories = histories;
    }

    @Transactional(readOnly = true)
    public List<GitHubDtos.ProgressPushResponse> newest(Long userId) {
        return histories.findTop20ByUserIdOrderByRequestedAtDescIdDesc(userId)
                .stream().map(GitHubProgressHistoryService::response).toList();
    }

    private static GitHubDtos.ProgressPushResponse response(
            GitHubProgressPushHistory history) {
        return new GitHubDtos.ProgressPushResponse(
                history.getId(), history.getTrigger(), history.getStatus(),
                history.getRequestedAt(), history.getStartedAt(),
                history.getCompletedAt(), history.getCommitSha(),
                history.getCommitUrl(), history.getChangedFiles(),
                history.getLastError());
    }
}
