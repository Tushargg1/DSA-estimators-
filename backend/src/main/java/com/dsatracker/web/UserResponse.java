package com.dsatracker.web;

import com.dsatracker.model.User;

/**
 * Response body returned by {@code POST /api/users} (and reusable by later
 * user-facing endpoints, e.g. task 8.2).
 *
 * <p>Exposes only the fields a client needs after onboarding: the generated
 * {@code id}, the display {@code name} and {@code email}, the linked platform
 * usernames, the {@code dailyTarget}, and {@code onboardingComplete}. The latter
 * is {@code false} immediately after creation because the historical backfill
 * (Requirement 1.4) runs asynchronously and flips the flag when it finishes
 * (Requirement 1.6).
 *
 * @param id                 generated user id
 * @param name               display name
 * @param email              account email
 * @param leetcodeUsername   linked LeetCode username, or {@code null}
 * @param codeforcesUsername linked Codeforces handle, or {@code null}
 * @param gfgUsername        linked GFG username, or {@code null}
 * @param dailyTarget        the user's daily target
 * @param onboardingComplete whether the one-time backfill has finished
 */
public record UserResponse(
        Long id,
        String name,
        String email,
        String leetcodeUsername,
        String codeforcesUsername,
        String gfgUsername,
        int dailyTarget,
        boolean onboardingComplete
) {
    /** Maps a persisted {@link User} entity to its API representation. */
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getLeetcodeUsername(),
                user.getCodeforcesUsername(),
                user.getGfgUsername(),
                user.getDailyTarget(),
                user.isOnboardingComplete());
    }
}
