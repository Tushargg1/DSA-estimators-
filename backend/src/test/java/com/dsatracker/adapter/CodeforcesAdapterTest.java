package com.dsatracker.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CodeforcesAdapter#parse(String, String)}, driven by
 * saved fixtures. No network access occurs.
 */
class CodeforcesAdapterTest {

    private CodeforcesAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CodeforcesAdapter(CodeforcesAdapter.DEFAULT_API_BASE, new ObjectMapper());
    }

    @Test
    @DisplayName("parse keeps only OK verdicts and maps contestId+index as problemId")
    void parseFiltersNonOkAndMapsFields() {
        List<RawSubmission> result =
                adapter.parse(FixtureLoader.load("codeforces-status.json"), "tourist");

        // The fixture has 3 OK verdicts and 2 non-OK (WRONG_ANSWER,
        // TIME_LIMIT_EXCEEDED are filtered out). One of the OK entries has no
        // contestId (gym/acmsguru) and is skipped, leaving 2 mapped submissions.
        assertEquals(2, result.size());

        RawSubmission first = result.get(0);
        assertEquals("1234A", first.problemId());
        assertEquals("Watermelon", first.problemName());
        assertEquals(Instant.ofEpochSecond(1700000000L), first.solvedAt());
        assertEquals("800", first.difficulty());
        assertEquals(List.of("math", "brute force"), first.tags());

        RawSubmission second = result.get(1);
        assertEquals("5678C", second.problemId());
        assertEquals("Dynamic Fun", second.problemName());
        assertEquals(Instant.ofEpochSecond(1700000200L), second.solvedAt());
        assertEquals("1500", second.difficulty());
        assertEquals(List.of("dp", "graphs"), second.tags());
    }

    @Test
    @DisplayName("parse returns an empty list when result is empty")
    void parseReturnsEmptyForEmptyResult() {
        List<RawSubmission> result =
                adapter.parse(FixtureLoader.load("codeforces-empty.json"), "tourist");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("parse throws when status is not OK")
    void parseThrowsWhenStatusNotOk() {
        String body = "{\"status\":\"FAILED\",\"comment\":\"handle: User not found\"}";
        assertThrows(RuntimeException.class, () -> adapter.parse(body, "nobody"));
    }

    @Test
    @DisplayName("parse throws on a malformed (non-JSON) body")
    void parseThrowsOnMalformedBody() {
        assertThrows(RuntimeException.class, () -> adapter.parse("not json at all", "tourist"));
    }

    @Test
    @DisplayName("parse throws on an empty body")
    void parseThrowsOnEmptyBody() {
        assertThrows(RuntimeException.class, () -> adapter.parse("", "tourist"));
    }

    @Test
    @DisplayName("parse skips an accepted submission that has no contestId")
    void parseSkipsSubmissionMissingContestId() {
        List<RawSubmission> result =
                adapter.parse(FixtureLoader.load("codeforces-status.json"), "tourist");

        // The fixture's gym-style OK entry has no contestId, so no stable
        // problemId can be formed and it must be skipped rather than mapped.
        boolean gymPresent = result.stream()
                .anyMatch(r -> "Gym Problem Without ContestId".equals(r.problemName()));
        assertTrue(!gymPresent, "submission without a contestId must be skipped");
    }
}
