package com.dsatracker.repository;

import com.dsatracker.model.DailyCount;
import com.dsatracker.model.DailyCountId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link DailyCount} (composite key
 * {@link DailyCountId} with fields {@code userId} and {@code dateIst}).
 */
@Repository
public interface DailyCountRepository extends JpaRepository<DailyCount, DailyCountId> {

    /**
     * A user's daily counts ordered by most recent day first, for streak
     * calculation.
     */
    List<DailyCount> findByIdUserIdOrderByIdDateIstDesc(Long userId);

    /**
     * A single day's count for a user, for the upsert path.
     */
    Optional<DailyCount> findByIdUserIdAndIdDateIst(Long userId, LocalDate dateIst);

    /** Re-evaluates stored hit flags without changing their raw counts. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE daily_counts
            SET target_hit = (count >= :target)
            WHERE user_id = :userId
            """, nativeQuery = true)
    int recomputeTargetHit(@Param("userId") Long userId, @Param("target") int target);
}
