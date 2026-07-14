package com.dsatracker.repository;

import com.dsatracker.model.Platform;
import com.dsatracker.model.Submission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Spring Data JPA repository for {@link Submission}.
 */
@Repository
public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    /**
     * Natural-key existence check {@code (user_id, platform, problem_id,
     * solved_at_utc)} so a poll re-run never double-inserts.
     */
    boolean existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
            Long userId, Platform platform, String problemId, Instant solvedAtUtc);

    /**
     * First-attempt check ignoring timestamp: has this user ever solved this
     * problem on this platform before?
     */
    boolean existsByUserIdAndPlatformAndProblemId(Long userId, Platform platform, String problemId);

    /**
     * Paginated submissions for a user, for the submissions API.
     */
    Page<Submission> findByUserId(Long userId, Pageable pageable);

    /**
     * Count of submissions that counted toward a user's daily target.
     */
    long countByUserIdAndCountedForTargetTrue(Long userId);

    /**
     * Count of a user's first-attempt submissions, i.e. distinct problems ever
     * solved across all platforms (Requirement 6.4, "total problems solved,
     * first-attempts only"). Unlike {@link #countByUserIdAndCountedForTargetTrue},
     * this includes backfilled history, since a first attempt is defined across
     * all recorded history regardless of whether it counted toward a target.
     *
     * <p>Uses explicit JPQL against the {@code isFirstAttempt} field to avoid the
     * JavaBeans property-name ambiguity a derived query would face on an
     * {@code is}-prefixed boolean field.
     */
    @Query("SELECT COUNT(s) FROM Submission s "
            + "WHERE s.userId = :userId AND s.isFirstAttempt = true")
    long countFirstAttemptsByUserId(@Param("userId") Long userId);
}
