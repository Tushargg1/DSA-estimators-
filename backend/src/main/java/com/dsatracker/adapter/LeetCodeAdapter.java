package com.dsatracker.adapter;

import com.dsatracker.adapter.exception.RateLimitException;
import com.dsatracker.model.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SubmissionFetcher} for LeetCode, backed by the unofficial GraphQL
 * endpoint's {@code recentAcSubmissionList} query.
 *
 * <p>HTTP client: Spring's {@link RestClient} (the synchronous client introduced
 * in Spring Framework 6.1 / Spring Boot 3.2, shipped with
 * {@code spring-boot-starter-web}) — a good fit here because the polling job is
 * blocking/imperative and does not need a reactive stack.
 *
 * <p>This adapter only produces the required fields ({@code problemId},
 * {@code problemName}, {@code solvedAt}); difficulty/tags are fetched separately
 * in task 3.3, so instances are created via {@link RawSubmission#of}.
 *
 * <p>Error handling (per design.md):
 * <ul>
 *   <li>HTTP 429 or 403 → {@link RateLimitException} (trigger platform cooldown).</li>
 *   <li>Any other non-2xx, transport failure, or schema mismatch → {@link RuntimeException};
 *       never returns partial/garbage data and never NPEs on missing fields.</li>
 *   <li>Valid user with no recent accepted submissions → empty list.</li>
 * </ul>
 */
@Component
public class LeetCodeAdapter implements SubmissionFetcher {

    private static final Logger log = LoggerFactory.getLogger(LeetCodeAdapter.class);

    static final String DEFAULT_ENDPOINT = "https://leetcode.com/graphql";

    /**
     * GraphQL request body template for the recent accepted submissions query.
     * {@code %s} is replaced with the (JSON-escaped) target username; the limit
     * is fixed at 20 recent items, sufficient for a 5-minute polling window.
     */
    private static final String RECENT_AC_QUERY =
            "query recentAcSubmissions($username: String!, $limit: Int!) { "
            + "recentAcSubmissionList(username: $username, limit: $limit) { "
            + "id title titleSlug timestamp } }";

    private static final int RECENT_LIMIT = 20;

    /**
     * GraphQL request body template for the single-problem metadata query.
     * {@code question(titleSlug: $titleSlug)} exposes {@code difficulty}
     * (EASY/MEDIUM/HARD) and {@code topicTags { name }} — the only two fields
     * we need to enrich a stored submission.
     */
    private static final String QUESTION_META_QUERY =
            "query questionMeta($titleSlug: String!) { "
            + "question(titleSlug: $titleSlug) { "
            + "difficulty topicTags { name } } }";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;

    /**
     * In-memory, process-lifetime cache of problem metadata keyed by
     * {@code titleSlug}. This is a <em>secondary</em> safeguard against redundant
     * network calls within a single run — the authoritative "don't re-fetch known
     * problems" gate is the DB existence check in the polling/backfill layer
     * (tasks 4/5), which never calls {@link #fetchProblemMeta(String)} for a
     * problem already persisted. Bounded implicitly by the number of distinct
     * problems a process observes; entries are cheap (a difficulty string + a few
     * tags). Problem metadata is effectively immutable, so a cached entry never
     * goes stale within a process lifetime.
     */
    private final ConcurrentHashMap<String, ProblemMeta> metadataCache = new ConcurrentHashMap<>();

    public LeetCodeAdapter(
            @Value("${leetcode.graphql.endpoint:" + DEFAULT_ENDPOINT + "}") String endpoint,
            ObjectMapper objectMapper) {
        this.endpoint = endpoint;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(endpoint)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Referer", "https://leetcode.com")
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
    }

    @Override
    public List<RawSubmission> fetchRecent(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("LeetCode username must not be blank");
        }

        String body;
        try {
            body = restClient.post()
                    .body(buildRequestBody(username))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        // 429 = explicit rate limit; 403 = Cloudflare/bot block on the
                        // unofficial endpoint — both treated as rate-limit signals.
                        if (status.value() == 429 || status.value() == 403) {
                            throw new RateLimitException(
                                    "LeetCode rate-limited request for user '" + username
                                    + "' (HTTP " + status.value() + ")");
                        }
                        throw new RuntimeException(
                                "LeetCode GraphQL returned non-success status " + status.value()
                                + " for user '" + username + "'");
                    })
                    .body(String.class);
        } catch (RateLimitException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    "LeetCode GraphQL request failed for user '" + username + "'", e);
        }

        return parse(body, username);
    }

    @Override
    public Platform platform() {
        return Platform.LEETCODE;
    }

    /**
     * Fetches best-effort metadata (difficulty + topic tags) for a single
     * LeetCode problem via the secondary {@code question(titleSlug:)} GraphQL
     * query.
     *
     * <p><b>Call frequency contract:</b> this is intentionally <em>not</em>
     * invoked from {@link #fetchRecent(String)} and MUST NOT be called on every
     * poll. The polling/backfill layer (design section 5) is responsible for
     * calling it exactly <em>once per new, unique problem</em> — i.e. only when
     * it encounters a {@code problemId} that is not already present in the
     * database. Problem metadata is immutable, so re-fetching it for a problem
     * already in the DB would waste calls against an unofficial, rate-limited
     * endpoint (see Requirement 8.1).
     *
     * <p>Metadata is best-effort (Requirement 8.2): if the problem cannot be
     * found (GraphQL {@code data.question} is null) this returns a
     * {@link ProblemMeta} with {@code null} difficulty and an empty tag list
     * rather than throwing — the downstream UI renders missing metadata as
     * "Not available". Missing individual fields are likewise null-safe and
     * never raise an NPE.
     *
     * <p>Error handling matches {@link #fetchRecent(String)}: HTTP 429/403 →
     * {@link RateLimitException}; any other transport/schema failure →
     * {@link RuntimeException}.
     *
     * <p>As a secondary, in-process safeguard against redundant calls (on top of
     * the DB gate described above) results are memoised in {@link #metadataCache}
     * keyed by {@code titleSlug}, so repeated calls for the same slug within a
     * single process never re-hit the network. Failures are not cached, so a
     * later call can retry.
     *
     * @param titleSlug the LeetCode problem slug (e.g. {@code "two-sum"})
     * @return the problem's metadata, never {@code null}
     */
    public ProblemMeta fetchProblemMeta(String titleSlug) {
        if (titleSlug == null || titleSlug.isBlank()) {
            throw new IllegalArgumentException("LeetCode titleSlug must not be blank");
        }

        ProblemMeta cached = metadataCache.get(titleSlug);
        if (cached != null) {
            return cached;
        }

        String body;
        try {
            body = restClient.post()
                    .body(buildMetaRequestBody(titleSlug))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 429 || status.value() == 403) {
                            throw new RateLimitException(
                                    "LeetCode rate-limited metadata request for problem '"
                                    + titleSlug + "' (HTTP " + status.value() + ")");
                        }
                        throw new RuntimeException(
                                "LeetCode GraphQL returned non-success status " + status.value()
                                + " for problem '" + titleSlug + "'");
                    })
                    .body(String.class);
        } catch (RateLimitException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    "LeetCode GraphQL metadata request failed for problem '" + titleSlug + "'", e);
        }

        ProblemMeta meta = parseProblemMeta(body, titleSlug);
        metadataCache.put(titleSlug, meta);
        return meta;
    }

    /**
     * Builds the GraphQL request payload for the single-problem metadata query.
     * Kept separate so the request shape is easy to unit test.
     */
    String buildMetaRequestBody(String titleSlug) {
        try {
            var root = objectMapper.createObjectNode();
            root.put("operationName", "questionMeta");
            var variables = root.putObject("variables");
            variables.put("titleSlug", titleSlug);
            root.put("query", QUESTION_META_QUERY);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build LeetCode GraphQL metadata request body", e);
        }
    }

    /**
     * Parses a LeetCode {@code question} metadata response into a
     * {@link ProblemMeta}.
     *
     * <p>Separated from the HTTP call so parsing can be unit-tested against saved
     * fixtures (task 3.6) without any network access. Fully null-safe: an absent
     * or explicitly-null {@code data.question} yields a {@code ProblemMeta} with
     * {@code null} difficulty and an empty tag list (metadata is best-effort,
     * Requirement 8.2). A GraphQL {@code errors} payload with no {@code data},
     * or a body that isn't valid JSON, raises a {@link RuntimeException}.
     *
     * @param body      raw JSON response body
     * @param titleSlug the queried slug (for diagnostics)
     * @return parsed metadata, never {@code null}
     */
    ProblemMeta parseProblemMeta(String body, String titleSlug) {
        if (body == null || body.isBlank()) {
            throw new RuntimeException(
                    "LeetCode GraphQL returned an empty body for problem '" + titleSlug + "'");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(
                    "LeetCode GraphQL metadata response was not valid JSON for problem '"
                    + titleSlug + "'", e);
        }

        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            if (root.has("errors")) {
                throw new RuntimeException(
                        "LeetCode GraphQL returned errors for problem '" + titleSlug + "': "
                        + root.get("errors"));
            }
            throw new RuntimeException(
                    "LeetCode GraphQL metadata response missing 'data' for problem '"
                    + titleSlug + "'");
        }

        JsonNode question = data.get("question");
        // Best-effort: unknown/missing problem → empty metadata, not an error.
        if (question == null || question.isNull()) {
            return new ProblemMeta(null, List.of());
        }

        String difficulty = text(question, "difficulty");

        List<String> tags = new ArrayList<>();
        JsonNode topicTags = question.get("topicTags");
        if (topicTags != null && topicTags.isArray()) {
            for (JsonNode tag : topicTags) {
                String name = text(tag, "name");
                if (name != null) {
                    tags.add(name);
                }
            }
        }

        return new ProblemMeta(difficulty, tags);
    }

    /**
     * Builds the GraphQL request payload for the recent-submissions query.
     * Kept separate so the request shape is easy to unit test.
     */
    String buildRequestBody(String username) {
        try {
            var root = objectMapper.createObjectNode();
            root.put("operationName", "recentAcSubmissions");
            var variables = root.putObject("variables");
            variables.put("username", username);
            variables.put("limit", RECENT_LIMIT);
            root.put("query", RECENT_AC_QUERY);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build LeetCode GraphQL request body", e);
        }
    }

    /**
     * Parses a LeetCode GraphQL response body into {@link RawSubmission}s.
     *
     * <p>Separated from the HTTP call so parsing can be unit-tested against saved
     * fixtures (task 3.6) without any network access. Null-safe on every field:
     * an empty or missing {@code recentAcSubmissionList} yields an empty list,
     * and any structural problem or unparseable field raises a
     * {@link RuntimeException} rather than an NPE / partial result.
     *
     * @param body     raw JSON response body
     * @param username the queried username (for diagnostics)
     * @return parsed submissions, never {@code null}
     */
    List<RawSubmission> parse(String body, String username) {
        if (body == null || body.isBlank()) {
            throw new RuntimeException(
                    "LeetCode GraphQL returned an empty body for user '" + username + "'");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(
                    "LeetCode GraphQL response was not valid JSON for user '" + username + "'", e);
        }

        // A GraphQL "errors" array with no data indicates an upstream/schema problem.
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            if (root.has("errors")) {
                throw new RuntimeException(
                        "LeetCode GraphQL returned errors for user '" + username + "': "
                        + root.get("errors"));
            }
            throw new RuntimeException(
                    "LeetCode GraphQL response missing 'data' for user '" + username + "'");
        }

        JsonNode list = data.get("recentAcSubmissionList");
        // Null-safe: absent or explicit null → treat as "no recent activity".
        if (list == null || list.isNull()) {
            return List.of();
        }
        if (!list.isArray()) {
            throw new RuntimeException(
                    "LeetCode GraphQL 'recentAcSubmissionList' was not an array for user '"
                    + username + "'");
        }

        List<RawSubmission> results = new ArrayList<>();
        for (JsonNode item : list) {
            results.add(toRawSubmission(item, username));
        }
        return results;
    }

    private RawSubmission toRawSubmission(JsonNode item, String username) {
        String titleSlug = text(item, "titleSlug");
        String title = text(item, "title");
        JsonNode timestampNode = item.get("timestamp");

        if (titleSlug == null || title == null || timestampNode == null || timestampNode.isNull()) {
            throw new RuntimeException(
                    "LeetCode submission missing required field(s) for user '" + username
                    + "': " + item);
        }

        long epochSeconds;
        try {
            // timestamp arrives as a unix-seconds string (may occasionally be numeric).
            epochSeconds = timestampNode.isNumber()
                    ? timestampNode.asLong()
                    : Long.parseLong(timestampNode.asText().trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException(
                    "LeetCode submission had an unparseable timestamp for user '" + username
                    + "': " + timestampNode, e);
        }

        Instant solvedAt = Instant.ofEpochSecond(epochSeconds);
        // Difficulty/tags are fetched by the secondary metadata query in task 3.3.
        return RawSubmission.of(titleSlug, title, solvedAt);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText();
        return s.isEmpty() ? null : s;
    }

    /**
     * Best-effort metadata for a single LeetCode problem, returned by
     * {@link #fetchProblemMeta(String)}.
     *
     * <p>A dedicated small record (rather than reusing {@link RawSubmission})
     * keeps the metadata-only call intent explicit: it carries no submission
     * identity/timestamp, only the two enrichment fields the DB stores.
     *
     * @param difficulty problem difficulty (EASY/MEDIUM/HARD), or {@code null}
     *                   if the problem was not found or the field was absent
     * @param tags       problem topic tags; never {@code null}, empty when none
     */
    public record ProblemMeta(String difficulty, List<String> tags) {
    }
}
