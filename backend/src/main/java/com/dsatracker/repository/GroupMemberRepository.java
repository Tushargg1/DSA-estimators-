package com.dsatracker.repository;

import com.dsatracker.model.GroupMember;
import com.dsatracker.model.GroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link GroupMember} (composite key
 * {@link GroupMemberId} with fields {@code groupId} and {@code userId}).
 */
@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    /**
     * All memberships for a user (look up the groups a user belongs to).
     */
    List<GroupMember> findByIdUserId(Long userId);

    /**
     * All memberships for a group (look up a group's members).
     */
    List<GroupMember> findByIdGroupId(Long groupId);
}
