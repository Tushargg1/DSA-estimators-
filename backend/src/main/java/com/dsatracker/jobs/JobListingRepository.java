package com.dsatracker.jobs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobListingRepository extends JpaRepository<JobListing, Long> {
    Page<JobListing> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
