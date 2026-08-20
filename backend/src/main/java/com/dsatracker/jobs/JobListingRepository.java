package com.dsatracker.jobs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobListingRepository extends JpaRepository<JobListing, Long> {
    Page<JobListing> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
    boolean existsByJobUrlIgnoreCase(String jobUrl);
    List<JobListing> findBySourceIdOrderByCreatedAtDesc(Long sourceId);
}
