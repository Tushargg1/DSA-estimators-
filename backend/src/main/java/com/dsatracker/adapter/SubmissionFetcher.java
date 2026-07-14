package com.dsatracker.adapter;

import com.dsatracker.adapter.exception.RateLimitException;
import com.dsatracker.adapter.exception.ScrapeException;
import com.dsatracker.model.Platform;

import java.util.List;

/**
 * Common abstraction over the three external platform integrations
 * (LeetCode, Codeforces, GFG). Each implementation knows how to talk to one
 * platform and return that user's recent solved problems in a platform-neutral
 * shape.
 *
 * <p>Implementations live in tasks 3.2–3.5:
 * {@code LeetCodeAdapter}, {@code CodeforcesAdapter}, {@code GfgAdapter}.
 *
 * <p>Validates: Requirement 2.1 — the polling job uses this interface to fetch
 * recent accepted/correct submissions for every active user across all linked
 * platforms.
 */
public interface SubmissionFetcher {

    /**
     * Fetch the most recent accepted/correct submissions for the given
     * platform username. Intended for the 5-minute polling cycle, so
     * implementations should request a small recent window (the design notes a
     * ~24h / ~20-item window is sufficient) rather than full history.
     *
     * <p>Returns an empty list (never {@code null}) when the user has no recent
     * submissions or the profile is reachable but empty.
     *
     * @param username the platform-specific public username/handle
     * @return recent submissions, most-recent-first where the platform allows;
     *         never {@code null}
     * @throws RateLimitException if the platform signals a rate limit; the caller
     *         should back off this platform for the configured cooldown period
     * @throws ScrapeException if a scraping-based implementation cannot parse the
     *         profile page (e.g. GFG markup changed)
     */
    List<RawSubmission> fetchRecent(String username);

    /**
     * Identifies which platform this implementation serves.
     *
     * <p>Used by the polling job to select the right adapter per linked account
     * and to key {@code poll_status} updates by platform.
     *
     * @return the {@link Platform} this adapter handles
     */
    Platform platform();
}
