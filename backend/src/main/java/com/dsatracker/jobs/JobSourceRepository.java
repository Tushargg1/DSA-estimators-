package com.dsatracker.jobs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobSourceRepository extends JpaRepository<JobSource, Long> {
    List<JobSource> findAllByOrderByCreatedAtDesc();
}
