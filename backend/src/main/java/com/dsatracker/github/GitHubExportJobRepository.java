package com.dsatracker.github;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GitHubExportJobRepository extends JpaRepository<GitHubExportJob, Long> {
    Optional<GitHubExportJob> findByCaptureId(Long captureId);

    @Modifying
    @Query(value = """
            INSERT INTO github_export_jobs
              (capture_id, status, attempts, next_attempt_at, created_at, updated_at)
            VALUES (:captureId, 'PENDING', 0, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE
              status = 'PENDING', attempts = 0, next_attempt_at = UTC_TIMESTAMP(6),
              lease_until = NULL, claim_token = NULL, last_error = NULL,
              exported_at = NULL, updated_at = UTC_TIMESTAMP(6)
            """, nativeQuery = true)
    int enqueue(@Param("captureId") Long captureId);

    @Query(value = """
            SELECT id FROM github_export_jobs
            WHERE (status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6))
               OR (status = 'PROCESSING' AND lease_until < UTC_TIMESTAMP(6))
            ORDER BY next_attempt_at, id
            LIMIT :limit FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findDueIdsForUpdate(@Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE github_export_jobs j
            JOIN solution_captures c ON c.id = j.capture_id
            SET j.next_attempt_at = UTC_TIMESTAMP(6), j.updated_at = UTC_TIMESTAMP(6)
            WHERE c.user_id = :userId AND j.status = 'PENDING'
            """, nativeQuery = true)
    int makePendingDueForUser(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from GitHubExportJob j where j.id = :id")
    Optional<GitHubExportJob> findByIdForUpdate(@Param("id") Long id);
}
