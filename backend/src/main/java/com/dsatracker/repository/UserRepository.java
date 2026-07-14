package com.dsatracker.repository;

import com.dsatracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link User}.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * All users that have completed onboarding, used by the polling job to
     * iterate only over active, ready-to-poll users.
     */
    List<User> findAllByOnboardingCompleteTrue();
}
