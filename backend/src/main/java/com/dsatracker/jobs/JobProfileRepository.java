package com.dsatracker.jobs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobProfileRepository extends JpaRepository<JobProfile, Long> {
    List<JobProfile> findByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserId(Long userId);
}
