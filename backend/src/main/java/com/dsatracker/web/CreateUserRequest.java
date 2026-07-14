package com.dsatracker.web;

/**
 * Request body for {@code POST /api/users} (task 8.1).
 *
 * <p>Carries the onboarding fields from Requirement 1.1: a display {@code name},
 * an {@code email} (the account's unique identity), and the optional platform
 * usernames the user wants to link ({@code leetcodeUsername},
 * {@code codeforcesUsername}, {@code gfgUsername} — the GFG field accepts a
 * profile username/handle).
 *
 * <p>{@code dailyTarget} is optional; when omitted the user is created with the
 * default target of 5 (Requirement 4.1). All validation (required fields,
 * duplicate email, per-platform existence checks) is performed in
 * {@link com.dsatracker.service.UserService}; this record is a plain,
 * framework-agnostic carrier so it stays usable without
 * {@code spring-boot-starter-validation} on the classpath.
 *
 * @param name               display name (required)
 * @param email              unique account email (required)
 * @param leetcodeUsername   LeetCode username, or {@code null}/blank if not linked
 * @param codeforcesUsername Codeforces handle, or {@code null}/blank if not linked
 * @param gfgUsername        GeeksforGeeks username, or {@code null}/blank if not linked
 * @param dailyTarget        optional per-user daily target; defaults to 5 when {@code null}
 */
public record CreateUserRequest(
        String name,
        String email,
        String leetcodeUsername,
        String codeforcesUsername,
        String gfgUsername,
        Integer dailyTarget
) {
}
