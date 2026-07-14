package com.dsatracker.web;

/**
 * Request body for {@code PUT /api/users/{id}/target} (task 8.3, Requirement 4.1).
 *
 * <p>Carries the new per-user daily target. The value is validated in
 * {@link com.dsatracker.service.UserService#updateDailyTarget(Long, Integer)}:
 * it must be present and at least {@code 1}. A missing or below-minimum value is
 * rejected with {@code 400 Bad Request} so a user can never set a nonsensical
 * (zero/negative) target.
 *
 * @param target the desired daily target; must be {@code >= 1}
 */
public record UpdateTargetRequest(Integer target) {
}
