package com.dsatracker.dto;

import com.dsatracker.model.Group;

/**
 * Response body describing a group, returned by {@code POST /api/groups} and
 * {@code POST /api/groups/join} (task 8.4).
 *
 * @param id         generated group id
 * @param name       group display name
 * @param inviteCode the unique invite code others use to join (Requirement 6.1)
 * @param createdBy  the id of the user who created the group
 */
public record GroupResponse(
        Long id,
        String name,
        String inviteCode,
        Long createdBy
) {
    /** Maps a persisted {@link Group} entity to its API representation. */
    public static GroupResponse from(Group group) {
        return new GroupResponse(
                group.getId(),
                group.getName(),
                group.getInviteCode(),
                group.getCreatedBy());
    }
}
