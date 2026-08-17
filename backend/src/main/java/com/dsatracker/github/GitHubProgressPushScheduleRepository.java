package com.dsatracker.github;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface GitHubProgressPushScheduleRepository
        extends JpaRepository<GitHubProgressPushSchedule, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO github_progress_push_schedules
              (user_id, enabled, first_time, second_time, timezone,
               next_run_at, updated_at)
            VALUES (:userId, TRUE, '09:00:00', '21:00:00', 'Asia/Kolkata',
                    :nextRunAt, UTC_TIMESTAMP(6))
            """, nativeQuery = true)
    int insertDefaults(@Param("userId") Long userId,
                       @Param("nextRunAt") Instant nextRunAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GitHubProgressPushSchedule s where s.userId = :userId")
    Optional<GitHubProgressPushSchedule> findByUserIdForUpdate(
            @Param("userId") Long userId);
}
