package com.dsatracker.dto;

import com.dsatracker.model.Platform;
import com.dsatracker.model.PollStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Read-only view of a single platform's polling health, returned by
 * {@code GET /api/status/poll} (task 8.8, Requirement 9.2).
 *
 * <p>The frontend "last synced X min ago" indicator (task 10.6) consumes a list
 * of these — one entry per {@link Platform}, always all three, even for
 * platforms that have never polled yet (those come back with {@code null}
 * timestamps rather than being omitted) so the client can render a stable shape.
 *
 * @param platform                the platform name (e.g. {@code "LEETCODE"})
 * @param lastSuccessAt           timestamp of the last successful poll, or {@code null} if never
 * @param lastFailureAt           timestamp of the last failed poll, or {@code null} if never
 * @param lastFailureReason       human-readable reason for the last failure, or {@code null}
 * @param minutesSinceLastSuccess whole minutes since the last successful poll, or {@code null}
 *                                if there has never been a successful poll; clamped at {@code 0}
 *                                so clock skew can't produce a negative value
 */
public record PollStatusResponse(
        String platform,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        String lastFailureReason,
        Long minutesSinceLastSuccess) {

    /**
     * Builds a response for a platform that has a stored {@link PollStatus} row.
     *
     * @param status the persisted poll status (must not be {@code null})
     * @param now    reference instant used to derive {@code minutesSinceLastSuccess}
     * @return the DTO view of {@code status}
     */
    public static PollStatusResponse from(PollStatus status, Instant now) {
        return new PollStatusResponse(
                status.getPlatform().name(),
                status.getLastSuccessAt(),
                status.getLastFailureAt(),
                status.getLastFailureReason(),
                minutesSince(status.getLastSuccessAt(), now));
    }

    /**
     * Builds a zero/null-filled response for a platform with no stored row yet, so
     * every {@link Platform} is always present in the endpoint's output.
     *
     * @param platform the platform that has never been polled
     * @return a DTO with the platform name and {@code null} health fields
     */
    public static PollStatusResponse empty(Platform platform) {
        return new PollStatusResponse(platform.name(), null, null, null, null);
    }

    private static Long minutesSince(Instant lastSuccessAt, Instant now) {
        if (lastSuccessAt == null) {
            return null;
        }
        long minutes = Duration.between(lastSuccessAt, now).toMinutes();
        return Math.max(0L, minutes);
    }
}
