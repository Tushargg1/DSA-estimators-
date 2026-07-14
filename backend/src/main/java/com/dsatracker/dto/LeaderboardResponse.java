package com.dsatracker.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/groups/{id}/leaderboard} (task 8.5,
 * Requirement 6.4).
 *
 * <p>Joins, per member, today's counted total against their daily target, their
 * current and longest streaks, and their all-time total of problems solved
 * (first-attempts only). Members are ordered by {@code todayCount} descending,
 * then {@code totalSolved} descending (see {@link GroupService#getLeaderboard}).
 *
 * @param groupId   the group's id
 * @param groupName the group's display name
 * @param members   one entry per group member, pre-sorted for display
 */
public record LeaderboardResponse(
        Long groupId,
        String groupName,
        List<MemberEntry> members
) {
    /**
     * A single member's leaderboard row.
     *
     * @param userId        the member's user id
     * @param userName      the member's display name
     * @param todayCount    counted submissions today (IST), 0 if none
     * @param dailyTarget   the member's configured daily target
     * @param currentStreak consecutive hit days ending today/yesterday
     * @param longestStreak longest consecutive-hit run in history
     * @param totalSolved   all-time count of problems that counted toward target
     */
    public record MemberEntry(
            Long userId,
            String userName,
            int todayCount,
            int dailyTarget,
            int currentStreak,
            int longestStreak,
            long totalSolved
    ) {
    }
}
