package com.dsatracker.dto;

/**
 * Request body for {@code POST /api/groups/join} (task 8.4, Requirements 6.2, 6.3).
 *
 * @param inviteCode the group's invite code
 * @param userId     the id of the user joining the group
 */
public record JoinGroupRequest(
        String inviteCode,
        Long userId
) {
}
