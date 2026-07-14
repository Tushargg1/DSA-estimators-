package com.dsatracker.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The single source of truth for all UTC &rarr; IST date bucketing in the
 * codebase.
 *
 * <p>All submission timestamps are stored in UTC ({@link Instant}). Whenever the
 * application needs to know which "counting day" a submission belongs to, it
 * MUST route the conversion through {@link #toIstDate(Instant)}. No other class
 * should perform ad-hoc timezone arithmetic — keeping the math in one place is
 * what makes day bucketing deterministic (design.md "Property 5") and avoids the
 * off-by-timezone bugs called out in the design's error-handling table.
 *
 * <h2>The counting day and the 12:01 AM nuance</h2>
 *
 * Requirement 4.2 phrases the reset as "12:01 AM IST", and the Glossary defines a
 * counting day as the window from 12:01 AM IST to 11:59:59 PM IST. That "12:01"
 * is the human-facing reset moment (a display/UX convention), not a distinct
 * bucketing rule.
 *
 * <p><b>Chosen, deterministic interpretation:</b> a submission's counting date is
 * simply its calendar date in the {@code Asia/Kolkata} zone — i.e. the
 * {@link LocalDate} obtained by converting the UTC {@link Instant} to IST. A
 * submission at 00:00:00–00:00:59 IST (the 60-second gap between calendar
 * midnight and 12:01 AM) is bucketed into the day that just started, NOT the
 * previous day. This keeps the rule a single, testable calendar-date conversion
 * with no special-cased minute, and it is the contract task 6.2's boundary tests
 * rely on:
 * <ul>
 *   <li>23:58 IST &rarr; that calendar date.</li>
 *   <li>00:02 IST &rarr; the next calendar date (the new day).</li>
 *   <li>00:00:30 IST &rarr; the new calendar date (the new day), by this contract.</li>
 * </ul>
 *
 * <p>IST is {@code Asia/Kolkata} (a fixed UTC+05:30 offset with no daylight
 * saving), so the conversion is stable across the year.
 */
public final class TimeUtil {

    /**
     * The India Standard Time zone (UTC+05:30, no DST). Exposed so callers that
     * legitimately need the zone (e.g. for display formatting) reference this one
     * constant rather than re-declaring {@code ZoneId.of("Asia/Kolkata")}.
     */
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private TimeUtil() {
        // Utility class — not instantiable.
    }

    /**
     * Converts a UTC instant to the IST calendar date it falls on — the single
     * source of truth for day bucketing.
     *
     * <p>See the class Javadoc for the exact 12:01 AM contract: this returns the
     * {@code Asia/Kolkata} {@link LocalDate} of the instant, so the 00:00:00–00:00:59
     * IST minute belongs to the day that just started.
     *
     * @param utcTimestamp the submission timestamp in UTC; must not be {@code null}
     * @return the IST calendar date the instant falls on
     * @throws NullPointerException if {@code utcTimestamp} is {@code null}
     */
    public static LocalDate toIstDate(Instant utcTimestamp) {
        if (utcTimestamp == null) {
            throw new NullPointerException("utcTimestamp must not be null");
        }
        return utcTimestamp.atZone(IST).toLocalDate();
    }
}
