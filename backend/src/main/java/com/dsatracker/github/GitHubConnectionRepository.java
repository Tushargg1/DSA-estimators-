package com.dsatracker.github;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GitHubConnectionRepository extends JpaRepository<GitHubConnection, Long> {
    Optional<GitHubConnection> findByExtensionTokenHash(String extensionTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GitHubConnection c where c.userId = :userId")
    Optional<GitHubConnection> findByUserIdForUpdate(@Param("userId") Long userId);
}
