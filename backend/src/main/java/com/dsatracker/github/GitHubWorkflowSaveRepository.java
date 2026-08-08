package com.dsatracker.github;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GitHubWorkflowSaveRepository
        extends JpaRepository<GitHubWorkflowSave, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO github_workflow_saves
              (user_id, request_token, status, requested_at, updated_at)
            VALUES (:userId, :requestToken, 'QUEUED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE
              request_token = IF(status IN ('QUEUED', 'RUNNING'), request_token,
                                 VALUES(request_token)),
              requested_at = IF(status IN ('QUEUED', 'RUNNING'), requested_at,
                                VALUES(requested_at)),
              lease_until = IF(status IN ('QUEUED', 'RUNNING'), lease_until, NULL),
              last_error = IF(status IN ('QUEUED', 'RUNNING'), last_error, NULL),
              status = IF(status IN ('QUEUED', 'RUNNING'), status, 'QUEUED'),
              updated_at = UTC_TIMESTAMP(6)
            """, nativeQuery = true)
    int queue(@Param("userId") Long userId,
              @Param("requestToken") String requestToken);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE github_workflow_saves
            SET status = 'RUNNING',
                lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 MINUTE),
                last_error = NULL,
                updated_at = UTC_TIMESTAMP(6)
            WHERE user_id = :userId
              AND (status = 'QUEUED' OR
                   (status = 'RUNNING' AND lease_until < UTC_TIMESTAMP(6)))
            """, nativeQuery = true)
    int claim(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE github_workflow_saves
            SET status = 'SUCCEEDED', lease_until = NULL,
                last_saved_at = UTC_TIMESTAMP(6), last_error = NULL,
                updated_at = UTC_TIMESTAMP(6)
            WHERE user_id = :userId AND request_token = :requestToken
              AND status = 'RUNNING'
            """, nativeQuery = true)
    int complete(@Param("userId") Long userId,
                 @Param("requestToken") String requestToken);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE github_workflow_saves
            SET status = 'FAILED', lease_until = NULL, last_error = :error,
                updated_at = UTC_TIMESTAMP(6)
            WHERE user_id = :userId AND request_token = :requestToken
              AND status = 'RUNNING'
            """, nativeQuery = true)
    int fail(@Param("userId") Long userId,
             @Param("requestToken") String requestToken,
             @Param("error") String error);
}
