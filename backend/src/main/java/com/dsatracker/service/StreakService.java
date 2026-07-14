package com.dsatracker.service;

import com.dsatracker.model.DailyCount;
import com.dsatracker.repository.DailyCountRepository;
import com.dsatracker.util.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Authoritative home for the streak / target-hit rules (Requirement 5).
 *
 * <h2>Task 7.1 — the {@code target_hit} rule</h2>
 * A single source of truth for the rule "a counting day is a <em>hit</em> day
 * when the user's counted submissions meet or exceed their daily target"
 * (Requirement 5.1):
 *
 * <pre>{@code target_hit = (count >= daily_target)}</pre>
 *
 * <p>{@link #isTargetHit(int, int)} is the only place this comparison is
 * expressed. {@link PollingService#updateDailyCounts} calls it every time a
 * day's {@code count} changes, so the {@code daily_counts.target_hit} column is
 * recomputed consistently on every upsert rather than via an ad-hoc inline
 * expression scattered across the codebase. That column — not the raw
 * submissions — is the single source of truth these streak calculations read.
 *
 * <h2>Tasks 7.2 / 7.3 — streak calculations</h2>
 * Both {@link #currentStreak(Long)} and {@link #longestStreak(Long)} read the
 * {@code daily_counts} rollup and treat a day as a "hit" day <em>only</em> when
 * {@code target_hit = true}. A day with a {@code daily_counts} row whose
 * {@code target_hit = false}, and a day with no row at all, are both treated as
 * <em>misses</em> that break a streak — the run must be over consecutive IST
 * calendar dates with no gaps.
 *
 * <p>All date math is done in IST calendar dates. "Today" is
 * {@code TimeUtil.toIstDate(clock.instant())}, driven by an injectable
 * {@link Clock} (defaulting to {@link Clock#systemUTC()}) so the "today /
 * yesterday" boundary is deterministic under test — the same clock approach
 * {@link PollingService} uses.
 *
 * <p>The bean holds no mutable state, so it is safe to share across threads.
 */
@Service
public class StreakService {

    private final DailyCountRepository dailyCountRepository;

    /**
     * Clock used to resolve "today" in IST. Defaults to {@link Clock#systemUTC()}
     * in production; the instant is converted to an IST calendar date via
     * {@link TimeUtil#toIstDate(java.time.Instant)}, so the zone of the clock
     * itself is irrelevant — only the instant matters.
     */
    private final Clock clock;

    /**
     * Production constructor: Spring injects the repository; the clock defaults to
     * {@link Clock#systemUTC()}.
     */
    @Autowired
    public StreakService(DailyCountRepository dailyCountRepository) {
        this(dailyCountRepository, Clock.systemUTC());
    }

    /**
     * Full constructor exposing the {@link Clock} for tests, so "today in IST" can
     * be pinned to a fixed instant and the today/yesterday edge case (Requirement
     * 5.2) exercised deterministically.
     */
    StreakService(DailyCountRepository dailyCountRepository, Clock clock) {
        this.dailyCountRepository = dailyCountRepository;
        this.clock = clock;
    }

    /**
     * The {@code target_hit} rule (Requirement 5.1): a day is a "hit" day when the
     * user's counted submissions for that day meet or exceed their daily target.
     *
     * <p>This is the single authoritative definition of {@code target_hit}; all
     * callers (currently {@link PollingService#updateDailyCounts}) must route
     * through here rather than re-deriving {@code count >= daily_target} inline.
     *
     * @param count       the day's counted-submission total (never negative in
     *                    practice; a negative count simply yields {@code false}
     *                    for any non-negative target)
     * @param dailyTarget the user's configured daily target
     * @return {@code true} iff {@code count >= dailyTarget}
     */
    public boolean isTargetHit(int count, int dailyTarget) {
        return count >= dailyTarget;
    }

    /**
     * Task 7.2 (Requirements 5.2, 5.3): the user's <em>current</em> streak — the
     * number of consecutive hit days ending <em>today</em> or, if today is not yet
     * a hit, <em>yesterday</em> (both in IST).
     *
     * <p>Semantics:
     * <ul>
     *   <li>If today is a hit day, count today and walk backwards day-by-day while
     *       each previous calendar day is also a hit, stopping at the first
     *       miss/gap.</li>
     *   <li>If today is <em>not</em> yet a hit but yesterday is (the user simply
     *       hasn't solved today yet), the streak is still alive: count back from
     *       yesterday instead (Requirement 5.2).</li>
     *   <li>If neither today nor yesterday is a hit, the current streak is 0.</li>
     * </ul>
     *
     * @param userId the user whose streak to compute
     * @return the current streak length in days (0 if none)
     */
    public int currentStreak(Long userId) {
        return currentStreak(hitDates(userId));
    }

    /**
     * Task 7.3 (Requirement 5.3): the user's <em>longest</em> streak — the maximum
     * length of any run of consecutive hit (IST calendar) days anywhere in their
     * history. Missing dates and non-hit days break runs.
     *
     * @param userId the user whose longest streak to compute
     * @return the longest consecutive-hit run in days (0 if the user has no hits)
     */
    public int longestStreak(Long userId) {
        return longestStreak(hitDates(userId));
    }

    /**
     * Convenience for callers (e.g. the leaderboard, task 8.5) that need both
     * numbers: computes the current and longest streak from a <em>single</em>
     * {@code daily_counts} read.
     *
     * @param userId the user whose streaks to compute
     * @return a {@link StreakInfo} carrying {@code current} and {@code longest}
     */
    public StreakInfo getStreaks(Long userId) {
        Set<LocalDate> hits = hitDates(userId);
        return new StreakInfo(currentStreak(hits), longestStreak(hits));
    }

    /**
     * Loads the set of IST calendar dates on which the user hit their target,
     * reading the {@code daily_counts} rollup and keeping only rows with
     * {@code target_hit = true}. Rows are not assumed contiguous — adjacency is
     * decided purely via {@link LocalDate} arithmetic downstream.
     */
    private Set<LocalDate> hitDates(Long userId) {
        List<DailyCount> rows = dailyCountRepository.findByIdUserIdOrderByIdDateIstDesc(userId);
        Set<LocalDate> hits = new HashSet<>();
        for (DailyCount row : rows) {
            if (row.isTargetHit()) {
                hits.add(row.getId().getDateIst());
            }
        }
        return hits;
    }

    /**
     * Current streak from a prepared set of hit dates. The anchor is today if today
     * is a hit, else yesterday if yesterday is a hit, else there is no live streak.
     * From the anchor we walk backwards one calendar day at a time while each day
     * remains a hit.
     */
    private int currentStreak(Set<LocalDate> hits) {
        LocalDate today = TimeUtil.toIstDate(clock.instant());
        LocalDate yesterday = today.minusDays(1);

        LocalDate anchor;
        if (hits.contains(today)) {
            anchor = today;
        } else if (hits.contains(yesterday)) {
            anchor = yesterday;
        } else {
            return 0;
        }

        int streak = 0;
        LocalDate day = anchor;
        while (hits.contains(day)) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    /**
     * Longest streak from a prepared set of hit dates. Each hit date whose previous
     * calendar day is <em>not</em> a hit is the start of a run; from each run start
     * we walk forward counting consecutive hits, tracking the maximum run seen.
     */
    private int longestStreak(Set<LocalDate> hits) {
        int longest = 0;
        for (LocalDate day : hits) {
            // Only start counting from the beginning of a run to keep this O(n).
            if (hits.contains(day.minusDays(1))) {
                continue;
            }
            int run = 0;
            LocalDate cursor = day;
            while (hits.contains(cursor)) {
                run++;
                cursor = cursor.plusDays(1);
            }
            if (run > longest) {
                longest = run;
            }
        }
        return longest;
    }

    /**
     * Combined current + longest streak for a user, for callers that want both in
     * one shot (e.g. the group leaderboard, task 8.5).
     *
     * @param current the current streak length in days
     * @param longest the longest streak length in days
     */
    public record StreakInfo(int current, int longest) {
    }
}
