package com.dsatracker.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sanity checks for {@link TimeUtil}. The exhaustive IST boundary suite (11:59 PM
 * UTC, exact IST midnight, etc.) is task 6.2; these assertions just pin the core
 * contract so it doesn't regress silently.
 */
class TimeUtilTest {

    @Test
    void istZoneConstantIsAsiaKolkata() {
        assertEquals(ZoneId.of("Asia/Kolkata"), TimeUtil.IST);
    }

    @Test
    void convertsUtcInstantToIstCalendarDate() {
        // 2024-01-01T18:30:00Z == 2024-01-02T00:00:00 IST (+05:30) -> new day.
        Instant utc = Instant.parse("2024-01-01T18:30:00Z");
        assertEquals(LocalDate.of(2024, 1, 2), TimeUtil.toIstDate(utc));
    }

    @Test
    void middayUtcStaysSameIstDate() {
        // 2024-06-15T06:00:00Z == 2024-06-15T11:30:00 IST -> same day.
        Instant utc = Instant.parse("2024-06-15T06:00:00Z");
        assertEquals(LocalDate.of(2024, 6, 15), TimeUtil.toIstDate(utc));
    }

    @Test
    void nullTimestampThrows() {
        assertThrows(NullPointerException.class, () -> TimeUtil.toIstDate(null));
    }

    // ------------------------------------------------------------------
    // Task 6.2 — IST boundary edge cases (Requirement 4.4).
    //
    // IST = Asia/Kolkata = UTC+05:30 (fixed, no DST). For every case below the
    // arithmetic is written out so the boundary reasoning is auditable:
    //     IST local time = UTC instant + 05:30
    //     toIstDate(...) = the Asia/Kolkata CALENDAR DATE of that local time
    // Per TimeUtil's documented contract, the 00:00:00-00:00:59 IST minute
    // belongs to the day that just started (the "12:01 reset" is a UX moment,
    // not a bucketing rule).
    // ------------------------------------------------------------------

    @Test
    void elevenFiftyNinePmUtcCrossesIntoNextIstDay() {
        // Case 1: 11:59 PM UTC.
        // 2024-01-01T23:59:00Z + 05:30 == 2024-01-02T05:29:00 IST.
        // The +05:30 push crosses midnight, so the counting date is Jan 2.
        Instant utc = Instant.parse("2024-01-01T23:59:00Z");
        assertEquals(LocalDate.of(2024, 1, 2), TimeUtil.toIstDate(utc));
    }

    @Test
    void exactIstMidnightBoundaryIsTheNewDay() {
        // Case 2: the UTC instant for exactly 00:00:00 IST.
        // 2024-01-01T00:00:00 IST - 05:30 == 2023-12-31T18:30:00Z.
        // 2023-12-31T18:30:00Z + 05:30 == 2024-01-01T00:00:00 IST -> new day.
        Instant utc = Instant.parse("2023-12-31T18:30:00Z");
        assertEquals(LocalDate.of(2024, 1, 1), TimeUtil.toIstDate(utc));
    }

    @Test
    void oneSecondBeforeIstMidnightStaysOnPreviousDay() {
        // Case 3: just BEFORE IST midnight.
        // 2023-12-31T18:29:59Z + 05:30 == 2023-12-31T23:59:59 IST -> still Dec 31.
        Instant utc = Instant.parse("2023-12-31T18:29:59Z");
        assertEquals(LocalDate.of(2023, 12, 31), TimeUtil.toIstDate(utc));
    }

    @Test
    void oneSecondAfterIstMidnightIsTheNewDay() {
        // Case 4: just AFTER IST midnight.
        // 2023-12-31T18:30:01Z + 05:30 == 2024-01-01T00:00:01 IST -> Jan 1.
        Instant utc = Instant.parse("2023-12-31T18:30:01Z");
        assertEquals(LocalDate.of(2024, 1, 1), TimeUtil.toIstDate(utc));
    }

    @Test
    void elevenFiftyEightPmIstLandsOnThatDay() {
        // Case 5a: 23:58 IST on 2024-03-10 (mirrors Requirement 4.4 / task 11.4).
        // 2024-03-10T23:58:00 IST - 05:30 == 2024-03-10T18:28:00Z.
        Instant utc = Instant.parse("2024-03-10T18:28:00Z");
        assertEquals(LocalDate.of(2024, 3, 10), TimeUtil.toIstDate(utc));
    }

    @Test
    void twoMinutesPastIstMidnightLandsOnNextDay() {
        // Case 5b: 00:02 IST on 2024-03-11 (the day after case 5a).
        // 2024-03-11T00:02:00 IST - 05:30 == 2024-03-10T18:32:00Z.
        // Sits just 4 minutes after the case-5a instant yet buckets to the next day.
        Instant utc = Instant.parse("2024-03-10T18:32:00Z");
        assertEquals(LocalDate.of(2024, 3, 11), TimeUtil.toIstDate(utc));
    }

    @Test
    void thirtySecondsPastIstMidnightIsTheNewDay() {
        // Case 6: the documented 00:00:30 IST case — pins the 12:01-vs-12:00 contract.
        // 2024-01-01T00:00:30 IST - 05:30 == 2023-12-31T18:30:30Z.
        // Falls in the 00:00:00-00:00:59 minute, which belongs to the day that
        // just started (Jan 1), NOT the previous day.
        Instant utc = Instant.parse("2023-12-31T18:30:30Z");
        assertEquals(LocalDate.of(2024, 1, 1), TimeUtil.toIstDate(utc));
    }

    @Test
    void middayNonCrossingControlCase() {
        // Case 7 (control): a mid-day instant that does not cross any boundary.
        // 2024-07-20T09:00:00Z + 05:30 == 2024-07-20T14:30:00 IST -> same day.
        Instant utc = Instant.parse("2024-07-20T09:00:00Z");
        assertEquals(LocalDate.of(2024, 7, 20), TimeUtil.toIstDate(utc));
    }
}
