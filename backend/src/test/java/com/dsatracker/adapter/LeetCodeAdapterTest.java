package com.dsatracker.adapter;

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
 * Unit tests for {@link LeetCodeAdapter}'s package-private parse methods, driven
 * by saved fixtures on the classpath. No network access occurs: only
 * {@link LeetCodeAdapter#parse(String, String)} and
 * {@link LeetCodeAdapter#parseProblemMeta(String, String)} are exercised.
 */
class LeetCodeAdapterTest {

    private LeetCodeAdapter adapter;

    @BeforeEach
    void setUp() {
        // Constructor only builds a RestClient; it makes no network call, so we
        // can instantiate freely and call the parse methods directly.
        adapter = new LeetCodeAdapter(LeetCodeAdapter.DEFAULT_ENDPOINT, new ObjectMapper());
    }

    @Test
    @DisplayName("parse maps titleSlug->problemId, title->name, timestamp->solvedAt")
    void parseMapsFieldsCorrectly() {
        List<RawSubmission> result =
                adapter.parse(FixtureLoader.load("leetcode-recent.json"), "testuser");

        assertEquals(2, result.size());

        RawSubmission first = result.get(0);
        assertEquals("two-sum", first.problemId());
        assertEquals("Two Sum", first.problemName());
        assertEquals(Instant.ofEpochSecond(1700000000L), first.solvedAt());
        // Difficulty/tags come from the secondary metadata query, not this one.
        assertNull(first.difficulty());
        assertNull(first.tags());

        RawSubmission second = result.get(1);
        assertEquals("add-two-numbers", second.problemId());
        assertEquals("Add Two Numbers", second.problemName());
        assertEquals(Instant.ofEpochSecond(1700100000L), second.solvedAt());
    }

    @Test
    @DisplayName("parse returns an empty list for an empty recentAcSubmissionList")
    void parseReturnsEmptyForEmptyList() {
        List<RawSubmission> result =
                adapter.parse(FixtureLoader.load("leetcode-recent-empty.json"), "testuser");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("parse throws on a GraphQL errors body (no data)")
    void parseThrowsOnGraphQlErrors() {
        String body = FixtureLoader.load("leetcode-errors.json");
        assertThrows(RuntimeException.class, () -> adapter.parse(body, "ghostuser"));
    }

    @Test
    @DisplayName("parse throws on a malformed (non-JSON) body")
    void parseThrowsOnMalformedBody() {
        assertThrows(RuntimeException.class, () -> adapter.parse("{not json", "testuser"));
    }

    @Test
    @DisplayName("parse throws on an empty body")
    void parseThrowsOnEmptyBody() {
        assertThrows(RuntimeException.class, () -> adapter.parse("", "testuser"));
    }

    @Test
    @DisplayName("parseProblemMeta returns difficulty and topic tags")
    void parseProblemMetaReturnsDifficultyAndTags() {
        LeetCodeAdapter.ProblemMeta meta =
                adapter.parseProblemMeta(FixtureLoader.load("leetcode-question.json"), "two-sum");

        assertEquals("Easy", meta.difficulty());
        assertEquals(List.of("Array", "Hash Table"), meta.tags());
    }

    @Test
    @DisplayName("parseProblemMeta degrades gracefully when data.question is null")
    void parseProblemMetaDegradesWhenQuestionNull() {
        LeetCodeAdapter.ProblemMeta meta =
                adapter.parseProblemMeta(FixtureLoader.load("leetcode-question-null.json"), "unknown");

        assertNull(meta.difficulty());
        assertNotNull(meta.tags());
        assertTrue(meta.tags().isEmpty());
    }

    @Test
    @DisplayName("parseProblemMeta throws on a GraphQL errors body")
    void parseProblemMetaThrowsOnErrors() {
        String body = FixtureLoader.load("leetcode-errors.json");
        assertThrows(RuntimeException.class, () -> adapter.parseProblemMeta(body, "two-sum"));
    }
}
