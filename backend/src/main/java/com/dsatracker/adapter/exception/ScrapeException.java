package com.dsatracker.adapter.exception;

/**
 * Thrown by a scraping-based {@link com.dsatracker.adapter.SubmissionFetcher}
 * (currently the GFG adapter) when a profile page cannot be parsed — typically
 * because the upstream markup changed.
 *
 * <p>Distinguishable from other failures so the polling job can log it as a
 * {@code GFG_PARSE_FAILURE} event and surface it on the internal status page
 * without crashing polling for other users/platforms, per Requirements 9.1/9.2.
 *
 * <p>Unchecked so it does not pollute {@code fetchRecent}'s signature.
 */
public class ScrapeException extends RuntimeException {

    public ScrapeException(String message) {
        super(message);
    }

    public ScrapeException(String message, Throwable cause) {
        super(message, cause);
    }
}
