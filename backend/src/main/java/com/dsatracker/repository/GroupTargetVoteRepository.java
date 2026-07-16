package com.dsatracker.repository;

import com.dsatracker.model.GroupTargetVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupTargetVoteRepository extends JpaRepository<GroupTargetVote, Long> {
    List<GroupTargetVote> findByGroupIdAndPollVersionOrderByProposedTargetAsc(Long groupId, int pollVersion);
    Optional<GroupTargetVote> findByGroupIdAndUserIdAndPollVersion(Long groupId, Long userId, int pollVersion);
}