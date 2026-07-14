package com.dsatracker.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Response body for {@code GET /api/groups/{id}/history?date=X} (task 8.6,
 * Requirement 4.5).
 *
 * <p>Reports each member's {@code daily_counts} row for the requested IST
 * calendar date. Members with no row for that day are zero-filled
 * ({@code count = 0}, {@code targetHit = false}).
 *
 * @param groupId the group's id
 * @param date    the requested IST calendar date
 * @param members one entry per group member for that date
 */
public record HistoryResponse(
        Long groupId,
        LocalDate date,
        List<HistoryEntry> members
) {
    /**
     * A single member's historical count for the requested date.
     *
     * @param userId    the member's user id
     * @param userName  the member's display name
     * @param count     counted submissions that day (0 if no row)
     * @param targetHit whether the day was a hit day (false if no row)
     */
    public record HistoryEntry(
            Long userId,
            String userName,
            int count,
            boolean targetHit
    ) {
    }
}
