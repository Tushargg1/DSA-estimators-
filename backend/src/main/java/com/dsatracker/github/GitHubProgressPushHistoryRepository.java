package com.dsatracker.github;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GitHubProgressPushHistoryRepository
        extends JpaRepository<GitHubProgressPushHistory, Long> {

    List<GitHubProgressPushHistory> findTop20ByUserIdOrderByRequestedAtDescIdDesc(
            Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO github_progress_push_history
              (user_id, request_token, trigger_type, status, requested_at)
            SELECT s.user_id, s.request_token, :triggerType, 'QUEUED', s.requested_at
            FROM github_workflow_saves s
            WHERE s.user_id = :userId AND s.request_token = :requestToken
              AND NOT EXISTS (
                SELECT 1 FROM github_progress_push_history h
                WHERE h.request_token = :requestToken)
            """, nativeQuery = true)
    int insertQueued(@Param("userId") Long userId,
                     @Param("requestToken") String requestToken,
                     @Param("triggerType") String triggerType);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE github_progress_push_history
            SET status = 'RUNNING',
                started_at = COALESCE(started_at, UTC_TIMESTAMP(6)),
                completed_at = NULL, last_error = NULL
            WHERE user_id = :userId AND request_token = :requestToken
              AND status IN ('QUEUED', 'RUNNING')
            """, nativeQuery = true)
    int markRunning(@Param("userId") Long userId,
                    @Param("requestToken") String requestToken);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE github_progress_push_history
            SET status = 'SUCCEEDED', completed_at = UTC_TIMESTAMP(6),
                commit_sha = :commitSha, commit_url = :commitUrl,
                changed_files = :changedFiles, last_error = NULL
            WHERE user_id = :userId AND request_token = :requestToken
              AND status = 'RUNNING'
            """, nativeQuery = true)
    int markSucceeded(@Param("userId") Long userId,
                      @Param("requestToken") String requestToken,
                      @Param("commitSha") String commitSha,
                      @Param("commitUrl") String commitUrl,
                      @Param("changedFiles") Integer changedFiles);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE github_progress_push_history
            SET status = 'FAILED', completed_at = UTC_TIMESTAMP(6),
                commit_sha = NULL, commit_url = NULL, changed_files = NULL,
                last_error = :error
            WHERE user_id = :userId AND request_token = :requestToken
              AND status = 'RUNNING'
            """, nativeQuery = true)
    int markFailed(@Param("userId") Long userId,
                   @Param("requestToken") String requestToken,
                   @Param("error") String error);
}
