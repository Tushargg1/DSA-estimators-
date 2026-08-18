package com.dsatracker.jobs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface JobApplicationRepository
        extends JpaRepository<JobApplication, JobApplicationId> {
    @Query("select a from JobApplication a where a.id.userId = :userId "
            + "and a.id.listingId in :listingIds")
    List<JobApplication> findForUserAndListings(
            @Param("userId") Long userId,
            @Param("listingIds") Collection<Long> listingIds);

    @Modifying
    @Query(value = "INSERT IGNORE INTO job_applications "
            + "(listing_id, user_id, applied_at) "
            + "VALUES (:listingId, :userId, CURRENT_TIMESTAMP(6))",
            nativeQuery = true)
    int markApplied(@Param("listingId") Long listingId,
                    @Param("userId") Long userId);

    @Modifying
    @Query("delete from JobApplication a where a.id.listingId = :listingId "
            + "and a.id.userId = :userId")
    int unmarkApplied(@Param("listingId") Long listingId,
                      @Param("userId") Long userId);
}
