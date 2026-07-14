package com.dsatracker.adapter;

import com.dsatracker.adapter.exception.RateLimitException;
import com.dsatracker.model.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SubmissionFetcher} for Codeforces, backed by the official
 * {@code user.status} REST API.
 *
 * <p>HTTP client: Spring's {@link RestClient} (mirroring {@link LeetCodeAdapter}) —
 * the polling job is blocking/imperative and does not need a reactive stack.
 *
 * <p>Polling endpoint: {@code GET {base}/user.status?handle={handle}&from=1&count=20}.
 * Onboarding uses the same verified {@code from}/{@code count} contract with
 * larger pages and continues until a short raw result page is returned.
 * The base URL is injectable via {@code ${codeforces.api.base}} (default
 * {@code https://codeforces.com/api}) so the adapter tests (task 3.6) can point
 * it at a local fixture server instead of hitting the live API.
 *
 * <p>Response shape:
 * <pre>{@code
 * { "status": "OK",
 *   "result": [
 *     { "verdict": "OK",
 *       "creationTimeSeconds": 1700000000,
 *       "problem": { "contestId": 1234, "index": "A", "name": "...",
 *                    "rating": 800, "tags": ["greedy", ...] } },
 *     ...
 *   ] }
 * }</pre>
 *
 * <p>Mapping choices (design.md Codeforces section):
 * <ul>
 *   <li>Only submissions with {@code verdict == "OK"} (accepted) are kept; all
 *       other verdicts are skipped.</li>
 *   <li>{@code problemId} is {@code contestId + index} (e.g. {@code "1234A"}).</li>
 *   <li>{@code problemName} is {@code problem.name}.</li>
 *   <li>{@code solvedAt} is {@code Instant.ofEpochSecond(creationTimeSeconds)}.</li>
 *   <li>{@code tags} are populated from {@code problem.tags}.</li>
 *   <li>Codeforces expresses difficulty as a numeric {@code rating}, not
 *       Easy/Medium/Hard, so {@code difficulty} is set to the rating as a string
 *       (e.g. {@code "800"}), or {@code null} when the problem is unrated.</li>
 * </ul>
 *
 * <p>Error handling (per design.md):
 * <ul>
 *   <li>HTTP 429 → {@link RateLimitException} (trigger platform cooldown).</li>
 *   <li>Any other non-2xx, transport failure, or schema mismatch → {@link RuntimeException};
 *       never returns partial/garbage data and never NPEs on missing fields.</li>
 *   <li>{@code status != "OK"} → {@link RuntimeException} carrying the API {@code comment}.</li>
 *   <li>Problems lacking a {@code contestId} (rare: gym / acmsguru) are skipped.</li>
 *   <li>Valid handle with no recent accepted submissions → empty list.</li>
 * </ul>
 */
@Component
public class CodeforcesAdapter implements SubmissionFetcher {

    private static final Logger log = LoggerFactory.getLogger(CodeforcesAdapter.class);

    static final String DEFAULT_API_BASE = "https://codeforces.com/api";

    /** Verdict indicating an accepted submission. */
    private static final String ACCEPTED_VERDICT = "OK";

    /** Number of recent submissions to request, sufficient for a 5-minute poll. */
    private static final int RECENT_COUNT = 20;

    /** Conservative page size for complete onboarding history pagination. */
    private static final int HISTORY_PAGE_SIZE = 1_000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiBase;

    public CodeforcesAdapter(
            @Value("${codeforces.api.base:" + DEFAULT_API_BASE + "}") String apiBase,
            ObjectMapper objectMapper) {
        this.apiBase = apiBase;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(apiBase)
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
    }

    @Override
    public List<RawSubmission> fetchRecent(String username) {
        validateUsername(username);
        return fetchPage(username, 1, RECENT_COUNT).submissions();
    }

    /**
     * Pages the official {@code user.status from/count} contract until the API
     * returns a short page. Termination uses the raw result size (not accepted
     * count), because rejected submissions are filtered from the returned data.
     */
    @Override
    public List<RawSubmission> fetchHistory(String username) {
        validateUsername(username);
        List<RawSubmission> history = new ArrayList<>();
        int from = 1;
        while (true) {
            SubmissionPage page = fetchPage(username, from, HISTORY_PAGE_SIZE);
            history.addAll(page.submissions());
            if (page.rawResultCount() < HISTORY_PAGE_SIZE) {
                return history;
            }
            from += HISTORY_PAGE_SIZE;
        }
    }

    private static void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Codeforces handle must not be blank");
        }
    }

    private SubmissionPage fetchPage(String username, int from, int count) {
        String body;
        try {
            body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/user.status")
                            .queryParam("handle", username)
                            .queryParam("from", from)
                            .queryParam("count", count)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 429) {
                            throw new RateLimitException(
                                    "Codeforces rate-limited request for handle '" + username
                                    + "' (HTTP 429)");
                        }
                        throw new RuntimeException(
                                "Codeforces user.status returned non-success status " + status.value()
                                + " for handle '" + username + "'");
                    })
                    .body(String.class);
        } catch (RateLimitException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    "Codeforces user.status request failed for handle '" + username + "'", e);
        }

        List<RawSubmission> submissions = parse(body, username);
        return new SubmissionPage(submissions, rawResultCount(body, username));
    }

    private int rawResultCount(String body, String username) {
        try {
            JsonNode result = objectMapper.readTree(body).get("result");
            return result != null && result.isArray() ? result.size() : 0;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Codeforces user.status response could not be counted for handle '"
                    + username + "'", e);
        }
    }

    private record SubmissionPage(List<RawSubmission> submissions, int rawResultCount) {
    }

    @Override
    public Platform platform() {
        return Platform.CODEFORCES;
    }

    /**
     * Parses a Codeforces {@code user.status} response body into
     * {@link RawSubmission}s.
     *
     * <p>Isolated from the HTTP call so parsing can be unit-tested against saved
     * fixtures (task 3.6) without network access. Only accepted
     * ({@code verdict == "OK"}) submissions are returned. Null-safe on every
     * field: an empty {@code result} yields an empty list, entries missing a
     * {@code contestId} are skipped, and any structural problem (non-JSON,
     * {@code status != "OK"}, {@code result} not an array) raises a
     * {@link RuntimeException} rather than an NPE / partial result.
     *
     * @param body     raw JSON response body
     * @param username the queried handle (for diagnostics)
     * @return parsed accepted submissions, never {@code null}
     */
    List<RawSubmission> parse(String body, String username) {
        if (body == null || body.isBlank()) {
            throw new RuntimeException(
                    "Codeforces user.status returned an empty body for handle '" + username + "'");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Codeforces user.status response was not valid JSON for handle '"
                    + username + "'", e);
        }

        String status = text(root, "status");
        if (!ACCEPTED_VERDICT.equals(status)) {
            String comment = text(root, "comment");
            throw new RuntimeException(
                    "Codeforces user.status returned status '" + status + "' for handle '"
                    + username + "'" + (comment != null ? ": " + comment : ""));
        }

        JsonNode result = root.get("result");
        // Null-safe: absent or explicit null → treat as "no recent activity".
        if (result == null || result.isNull()) {
            return List.of();
        }
        if (!result.isArray()) {
            throw new RuntimeException(
                    "Codeforces user.status 'result' was not an array for handle '"
                    + username + "'");
        }

        List<RawSubmission> results = new ArrayList<>();
        for (JsonNode item : result) {
            // Only accepted submissions count toward progress.
            String verdict = text(item, "verdict");
            if (!ACCEPTED_VERDICT.equals(verdict)) {
                continue;
            }
            RawSubmission submission = toRawSubmission(item, username);
            // toRawSubmission returns null for entries that must be skipped
            // gracefully (e.g. missing contestId on gym/acmsguru problems).
            if (submission != null) {
                results.add(submission);
            }
        }
        return results;
    }

    /**
     * Converts a single accepted {@code user.status} entry into a
     * {@link RawSubmission}, or returns {@code null} when the entry should be
     * skipped gracefully (missing {@code problem} node or {@code contestId}).
     *
     * @throws RuntimeException if a required field on an otherwise-valid entry is
     *         missing or unparseable (name, index, or creationTimeSeconds)
     */
    private RawSubmission toRawSubmission(JsonNode item, String username) {
        JsonNode problem = item.get("problem");
        if (problem == null || problem.isNull()) {
            // Malformed entry with no problem node — skip rather than NPE.
            log.debug("Codeforces submission missing 'problem' node for handle '{}', skipping", username);
            return null;
        }

        JsonNode contestIdNode = problem.get("contestId");
        if (contestIdNode == null || contestIdNode.isNull()) {
            // Rare: gym / acmsguru problems have no contestId. Cannot form a stable
            // problem_id, so skip gracefully per the design's guidance.
            log.debug("Codeforces problem missing 'contestId' for handle '{}', skipping", username);
            return null;
        }

        String index = text(problem, "index");
        String name = text(problem, "name");
        JsonNode creationNode = item.get("creationTimeSeconds");

        if (index == null || name == null || creationNode == null || creationNode.isNull()) {
            throw new RuntimeException(
                    "Codeforces submission missing required field(s) for handle '" + username
                    + "': " + item);
        }

        long epochSeconds;
        try {
            epochSeconds = creationNode.isNumber()
                    ? creationNode.asLong()
                    : Long.parseLong(creationNode.asText().trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException(
                    "Codeforces submission had an unparseable creationTimeSeconds for handle '"
                    + username + "': " + creationNode, e);
        }

        String problemId = contestIdNode.asText() + index;
        Instant solvedAt = Instant.ofEpochSecond(epochSeconds);

        // Codeforces has no Easy/Medium/Hard difficulty; it uses a numeric
        // rating. Surface the rating as the difficulty string (null when unrated).
        JsonNode ratingNode = problem.get("rating");
        String difficulty = (ratingNode != null && ratingNode.isNumber())
                ? String.valueOf(ratingNode.asInt())
                : null;

        List<String> tags = parseTags(problem.get("tags"));

        return new RawSubmission(problemId, name, solvedAt, difficulty, tags);
    }

    /**
     * Extracts tag names from a {@code tags} array node. Returns {@code null}
     * when the node is absent/null and an empty list when the array is present
     * but empty. Skips blank entries so a partial payload never NPEs.
     */
    private static List<String> parseTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return null;
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : tagsNode) {
            if (tag != null && !tag.isNull()) {
                String value = tag.asText();
                if (value != null && !value.isEmpty()) {
                    tags.add(value);
                }
            }
        }
        return tags;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText();
        return s.isEmpty() ? null : s;
    }
}
