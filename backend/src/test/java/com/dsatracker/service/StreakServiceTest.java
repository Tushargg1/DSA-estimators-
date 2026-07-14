package com.dsatracker.service;

import com.dsatracker.model.DailyCount;
import com.dsatracker.model.DailyCountId;
import com.dsatracker.repository.DailyCountRepository;
import com.dsatracker.util.TimeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StreakService} (tasks 7.2 / 7.3).
 *
 * <p>Database-free: {@link DailyCountRepository} is a Mockito mock returning a
 * scripted list of {@code daily_counts} rows, and "today in IST" is pinned via a
 * fixed {@link Clock} so the today/yesterday boundary (Requirement 5.2) is
 * deterministic. Concerns covered:
 * <ul>
 *   <li>current streak counts back from <em>today</em> when today is a hit;</li>
 *   <li>current streak counts back from <em>yesterday</em> when today has no hit
 *       yet but yesterday is a hit;</li>
 *   <li>current streak is 0 when neither today nor yesterday is a hit;</li>
 *   <li>a gap (missing date) breaks the current streak;</li>
 *   <li>longest streak finds the max run among several separated runs;</li>
 *   <li>{@code target_hit = false} days do not count as hits;</li>
 *   <li>empty history -> 0 and 0.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class StreakServiceTest {

    private static final long USER_ID = 42L;

    /**
     * A fixed instant whose IST calendar date is 2024-06-15. 2024-06-15T06:00:00Z
     * is 2024-06-15 11:30 IST, comfortably mid-day so there is no boundary
     * ambiguity.
     */
    private static final Instant NOW = Instant.parse("2024-06-15T06:00:00Z");
    private static final LocalDate TODAY = TimeUtil.toIstDate(NOW);

    @Mock
    private DailyCountRepository dailyCountRepository;

    private StreakService newService() {
        // Zone is irrelevant: StreakService converts the instant via TimeUtil.
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        return new StreakService(dailyCountRepository, fixed);
    }

    /**
     * Builds a {@code target_hit = true} row for the given IST date offset from
     * today (0 = today, -1 = yesterday, ...).
     */
    private DailyCount hit(long daysFromToday) {
        return row(daysFromToday, /*count*/ 5, /*targetHit*/ true);
    }

    /** Builds a {@code target_hit = false} row (a miss with a row present). */
    private DailyCount miss(long daysFromToday) {
        return row(daysFromToday, /*count*/ 2, /*targetHit*/ false);
    }

    private DailyCount row(long daysFromToday, int count, boolean targetHit) {
        LocalDate date = TODAY.plusDays(daysFromToday);
        return new DailyCount(new DailyCountId(USER_ID, date), count, targetHit);
    }

    /**
     * Stubs the repository to return the given rows ordered by date descending —
     * the contract of {@code findByIdUserIdOrderByIdDateIstDesc}.
     */
    private void stubRows(List<DailyCount> rows) {
        List<DailyCount> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing((DailyCount r) -> r.getId().getDateIst()).reversed());
        when(dailyCountRepository.findByIdUserIdOrderByIdDateIstDesc(USER_ID)).thenReturn(ordered);
    }

    // ------------------------------------------------------------------
    // Task 7.2: current streak.
    // ------------------------------------------------------------------

    @Test
    void currentStreakCountsBackFromTodayWhenTodayIsAHit() {
        // today, yesterday, day-before -> 3 consecutive hits ending today.
        stubRows(List.of(hit(0), hit(-1), hit(-2)));

        assertThat(newService().currentStreak(USER_ID)).isEqualTo(3);
    }

    @Test
    void currentStreakCountsBackFromYesterdayWhenTodayHasNoHitYet() {
        // No row for today at all; yesterday + the two days before are hits.
        // The streak is still alive counting back from yesterday (Req 5.2).
        stubRows(List.of(hit(-1), hit(-2), hit(-3)));

        assertThat(newService().currentStreak(USER_ID)).isEqualTo(3);
    }

    @Test
    void currentStreakCountsBackFromYesterdayWhenTodayIsPresentButAMiss() {
        // Today has a row but target not hit yet; yesterday chain is alive.
        stubRows(List.of(miss(0), hit(-1), hit(-2)));

        assertThat(newService().currentStreak(USER_ID)).isEqualTo(2);
    }

    @Test
    void currentStreakIsZeroWhenNeitherTodayNorYesterdayIsAHit() {
        // Last hit was two days ago -> the streak has already lapsed.
        stubRows(List.of(hit(-2), hit(-3), hit(-4)));

        assertThat(newService().currentStreak(USER_ID)).isZero();
    }

    @Test
    void currentStreakStopsAtAGap() {
        // today, yesterday are hits; day -2 is MISSING (gap); older hits don't count.
        stubRows(List.of(hit(0), hit(-1), hit(-3), hit(-4)));

        assertThat(newService().currentStreak(USER_ID)).isEqualTo(2);
    }

    @Test
    void currentStreakStopsAtAMissRow() {
        // today + yesterday hits, then a target_hit=false row breaks the run.
        stubRows(List.of(hit(0), hit(-1), miss(-2), hit(-3)));

        assertThat(newService().currentStreak(USER_ID)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Task 7.3: longest streak.
    // ------------------------------------------------------------------

    @Test
    void longestStreakFindsMaxRunAmongSeparatedRuns() {
        // Run A: days -10,-9 (length 2). Run B: days -6,-5,-4,-3 (length 4).
        // Run C: today only (length 1). Gaps separate all three.
        stubRows(List.of(
                hit(-10), hit(-9),
                hit(-6), hit(-5), hit(-4), hit(-3),
                hit(0)));

        assertThat(newService().longestStreak(USER_ID)).isEqualTo(4);
    }

    @Test
    void longestStreakIgnoresMissRowsWhenComputingRuns() {
        // A target_hit=false day in the middle splits what would otherwise be one run.
        // Hits: -4,-3 (run 2), then miss at -2, then -1,0 (run 2). Longest = 2.
        stubRows(List.of(hit(-4), hit(-3), miss(-2), hit(-1), hit(0)));

        assertThat(newService().longestStreak(USER_ID)).isEqualTo(2);
    }

    @Test
    void longestStreakOfASingleIsolatedHitDayIsOne() {
        // Exactly one hit day in all of history (isolated, no neighbours) -> 1.
        stubRows(List.of(hit(-7)));

        assertThat(newService().longestStreak(USER_ID)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Shared: misses and empty history.
    // ------------------------------------------------------------------

    @Test
    void targetHitFalseDaysDoNotCountAsHits() {
        // Every row present but none hit -> no streaks at all.
        stubRows(List.of(miss(0), miss(-1), miss(-2)));

        StreakService.StreakInfo info = newService().getStreaks(USER_ID);
        assertThat(info.current()).isZero();
        assertThat(info.longest()).isZero();
    }

    @Test
    void emptyHistoryYieldsZeroAndZero() {
        stubRows(List.of());

        StreakService.StreakInfo info = newService().getStreaks(USER_ID);
        assertThat(info.current()).isZero();
        assertThat(info.longest()).isZero();
    }

    @Test
    void getStreaksReturnsBothCurrentAndLongest() {
        // Current run: today,-1 (2). A longer, separated past run: -6..-3 (4),
        // with a gap at -2 so the two runs don't merge.
        stubRows(List.of(hit(0), hit(-1), hit(-6), hit(-5), hit(-4), hit(-3)));

        StreakService.StreakInfo info = newService().getStreaks(USER_ID);
        assertThat(info.current()).isEqualTo(2);
        assertThat(info.longest()).isEqualTo(4);
    }
}
