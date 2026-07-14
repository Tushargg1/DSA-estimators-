package com.dsatracker.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Request body for {@code POST /api/groups} (task 8.4, Requirement 6.1).
 *
 * <p>The creator's user id may arrive under either JSON key {@code createdByUserId}
 * or {@code createdBy}; {@link JsonAlias} accepts both so the frontend is not
 * pinned to one spelling.
 *
 * @param name            the display name of the group
 * @param createdByUserId the id of the user creating the group (also accepted as
 *                        {@code createdBy})
 */
public record CreateGroupRequest(
        String name,
        @JsonAlias("createdBy") Long createdByUserId
) {
}
