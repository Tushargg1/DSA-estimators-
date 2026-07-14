package com.dsatracker.adapter;

import com.dsatracker.adapter.exception.ScrapeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GfgAdapter#parse(String)}, driven by saved HTML
 * fixtures. No network access occurs: only the parse method runs against saved
 * profile HTML.
 */
class GfgAdapterTest {

    private GfgAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new GfgAdapter(GfgAdapter.DEFAULT_PROFILE_BASE, new ObjectMapper());
    }

    @Test
    @DisplayName("parse extracts solved problems from a valid __NEXT_DATA__ blob")
    void parseExtractsProblemsFromNextData() {
        List<RawSubmission> result =
                adapter.parse(FixtureLoader.load("gfg-profile.html"));

        assertEquals(3, result.size());

        RawSubmission first = result.get(0);
        assertEquals("reverse-a-string", first.problemId());
        assertEquals("Reverse a String", first.problemName());
        // Epoch seconds.
        assertEquals(Instant.ofEpochSecond(1700000000L), first.solvedAt());
        // GFG does not expose difficulty/tags.
        assertNull(first.difficulty());
        assertNull(first.tags());

        RawSubmission second = result.get(1);
        assertEquals("binary-search", second.problemId());
        assertEquals("Binary Search", second.problemName());
        // ISO-8601 string.
        assertEquals(Instant.parse("2023-11-15T10:00:00Z"), second.solvedAt());

        RawSubmission third = result.get(2);
        assertEquals("detect-loop-in-linked-list", third.problemId());
        // Epoch millis (13 digits) coerced correctly.
        assertEquals(Instant.ofEpochMilli(1700200000000L), third.solvedAt());
    }

    @Test
    @DisplayName("parse returns an empty list when the solved list is present but empty")
    void parseReturnsEmptyWhenSolvedListEmpty() {
        List<RawSubmission> result =
                adapter.parse(FixtureLoader.load("gfg-profile-empty.html"));

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("parse throws when a recognized nonempty solved list yields zero valid rows")
    void parseThrowsWhenNonemptySolvedListHasNoValidRows() {
        String html = profileHtmlWithSolvedProblems("""
                {"slug":"missing-timestamp","name":"Missing Timestamp"},
                {"name":"Missing Id","solvedAt":1700000000},
                {"slug":"invalid-time","name":"Invalid Time","solvedAt":"not-a-timestamp"}
                """);

        ScrapeException exception = assertThrows(ScrapeException.class, () -> adapter.parse(html));

        assertTrue(exception.getMessage().contains("3 entries"));
        assertTrue(exception.getMessage().contains("zero valid RawSubmission rows"));
        assertTrue(exception.getMessage().contains("GFG_PARSE_FAILURE"));
    }

    @Test
    @DisplayName("parse preserves valid rows when another solved-list entry is malformed")
    void parsePreservesPartialValidResults() {
        String html = profileHtmlWithSolvedProblems("""
                {"slug":"valid-problem","name":"Valid Problem","solvedAt":1700000000},
                {"slug":"missing-timestamp","name":"Missing Timestamp"}
                """);

        List<RawSubmission> result = adapter.parse(html);

        assertEquals(1, result.size());
        assertEquals("valid-problem", result.get(0).problemId());
    }

    @Test
    @DisplayName("parse throws ScrapeException for a client-rendered page (no __NEXT_DATA__)")
    void parseThrowsForClientRenderedPage() {
        String html = FixtureLoader.load("gfg-client-rendered.html");
        assertThrows(ScrapeException.class, () -> adapter.parse(html));
    }

    @Test
    @DisplayName("parse throws ScrapeException for malformed __NEXT_DATA__ JSON")
    void parseThrowsForMalformedJson() {
        String html = FixtureLoader.load("gfg-malformed.html");
        assertThrows(ScrapeException.class, () -> adapter.parse(html));
    }

    @Test
    @DisplayName("parse throws ScrapeException for empty HTML")
    void parseThrowsForEmptyHtml() {
        assertThrows(ScrapeException.class, () -> adapter.parse(""));
    }

    private static String profileHtmlWithSolvedProblems(String entries) {
        return """
                <!DOCTYPE html>
                <html><body>
                  <script id="__NEXT_DATA__" type="application/json">
                    {"props":{"pageProps":{"solvedProblems":[%s]}}}
                  </script>
                </body></html>
                """.formatted(entries);
    }
}
