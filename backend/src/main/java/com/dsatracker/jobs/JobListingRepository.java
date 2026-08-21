package com.dsatracker.jobs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface JobListingRepository extends JpaRepository<JobListing, Long> {
    Page<JobListing> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    boolean existsByJobUrlIgnoreCase(String jobUrl);

    List<JobListing> findBySourceIdOrderByCreatedAtDesc(Long sourceId);

    long countBySourceId(Long sourceId);

    /**
     * Dedup lookup for adapter-sourced jobs, keyed on the portal's own job id.
     * Returns the subset of the given ids that already exist for this source, so a
     * chunked sweep can insert only genuinely new jobs.
     */
    @Query("select l.externalId from JobListing l "
            + "where l.sourceId = :sourceId and l.externalId in :externalIds")
    Set<String> findExistingExternalIds(@Param("sourceId") Long sourceId,
                                       @Param("externalIds") Collection<String> externalIds);
}
