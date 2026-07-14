package com.dsatracker.dto;

import com.dsatracker.model.Submission;
import com.dsatracker.model.User;

/**
 * Live leaderboard delta pushed over WebSocket when a new counted submission is
 * detected during a poll cycle (task 9.3, Requirement 7.1; design.md "WebSocket
 * Design").
 *
 * <p>Published to {@code /topic/group/{groupId}} for every group the user
 * belongs to. It carries only the delta needed to update a leaderboard row in
 * place; clients that missed messages (e.g. after a dropped connection) resync
 * via {@code GET /api/groups/{id}/leaderboard} (Requirement 7.2).
 *
 * <p>The field set matches the design.md payload exactly:
 * {@code {userId, userName, problemName, platform, difficulty, newDailyCount,
 * target}}.
 *
 * @param userId        the user who solved the problem
 * @param userName      the user's display name (for rendering without a lookup)
 * @param problemName   the solved problem's name
 * @param platform      the platform name (e.g. {@code "LEETCODE"})
 * @param difficulty    difficulty if known, else {@code null} (e.g. GFG gap)
 * @param newDailyCount the user's counted total for that submission's IST day
 *                      after this solve was rolled up
 * @param target        the user's configured daily target
 */
public record LeaderboardUpdate(
        Long userId,
        String userName,
        String problemName,
        String platform,
        String difficulty,
        int newDailyCount,
        int target
) {

    /**
     * Builds an update from the user, the newly counted submission, and the
     * user's freshly upserted daily count for that submission's IST day.
     *
     * @param user          the user who owns the submission
     * @param submission    the newly counted submission
     * @param newDailyCount the day's counted total after the daily_counts upsert
     * @return a populated {@link LeaderboardUpdate}
     */
    public static LeaderboardUpdate from(User user, Submission submission, int newDailyCount) {
        return new LeaderboardUpdate(
                user.getId(),
                user.getName(),
                submission.getProblemName(),
                submission.getPlatform() == null ? null : submission.getPlatform().name(),
                submission.getDifficulty(),
                newDailyCount,
                user.getDailyTarget());
    }
}
