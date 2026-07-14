package com.dsatracker.repository;

import com.dsatracker.model.Platform;
import com.dsatracker.model.PollStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PollStatus}, keyed by {@link Platform}.
 */
@Repository
public interface PollStatusRepository extends JpaRepository<PollStatus, Platform> {
}
