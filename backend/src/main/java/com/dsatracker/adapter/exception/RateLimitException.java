package com.dsatracker.adapter.exception;

/**
 * Thrown by a {@link com.dsatracker.adapter.SubmissionFetcher} when the upstream
 * platform responds with a rate-limit signal (e.g. HTTP 429, or 403 for
 * LeetCode's unofficial GraphQL endpoint).
 *
 * <p>The polling job treats this as the trigger to back off the offending
 * platform for a configurable cooldown period (default 15 minutes) before
 * retrying, per Requirement 2.5.
 *
 * <p>Unchecked so it does not pollute {@code fetchRecent}'s signature.
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }

    public RateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
