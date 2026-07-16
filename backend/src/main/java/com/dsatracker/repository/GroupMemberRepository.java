package com.dsatracker.repository;

import com.dsatracker.model.GroupMember;
import com.dsatracker.model.GroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    boolean existsByIdGroupIdAndIdUserId(Long groupId, Long userId);

    Optional<GroupMember> findByIdGroupIdAndIdUserId(Long groupId, Long userId);

    long countByIdGroupIdAndJoinedAtLessThanEqual(Long groupId, Instant cutoff);

    @Query("""
            select (count(gm) > 0) from GroupMember gm
            where gm.id.userId = :firstUserId
              and gm.id.groupId in (
                select other.id.groupId from GroupMember other
                where other.id.userId = :secondUserId
              )
            """)
    boolean shareGroup(@Param("firstUserId") Long firstUserId,
                       @Param("secondUserId") Long secondUserId);

    /**
     * Atomically inserts a membership or leaves the existing row untouched.
     * The composite primary key makes concurrent repeat joins conflict-safe.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO group_members (group_id, user_id, joined_at)
            VALUES (:groupId, :userId, :joinedAt)
            ON DUPLICATE KEY UPDATE joined_at = joined_at
            """, nativeQuery = true)
    int insertIfAbsent(@Param("groupId") Long groupId,
                       @Param("userId") Long userId,
                       @Param("joinedAt") Instant joinedAt);
}
