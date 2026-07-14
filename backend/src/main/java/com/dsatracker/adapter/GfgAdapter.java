package com.dsatracker.adapter;

import com.dsatracker.adapter.exception.RateLimitException;
import com.dsatracker.adapter.exception.ScrapeException;
import com.dsatracker.model.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SubmissionFetcher} for GeeksforGeeks (GFG), backed by an HTML scrape of
 * the public profile page ({@code https://www.geeksforgeeks.org/user/{username}/}).
 *
 * <p>GFG has no official API, so this adapter is best-effort and inherently
 * fragile: it WILL break whenever GFG changes its markup. All such breakage is
 * surfaced as a distinguishable {@link ScrapeException} (never a raw
 * {@link RuntimeException} and never a {@link NullPointerException}) so the
 * polling job can log it as a {@code GFG_PARSE_FAILURE} event and expose it on
 * the internal status page without crashing polling for other users/platforms
 * (Requirements 9.1 / 9.2).
 *
 * <h2>Client-rendering caveat (important)</h2>
 * GFG's profile page is a Next.js app and is, in large part, <em>client-side
 * rendered</em>: a plain HTTP GET (which is all Jsoup does — it does NOT execute
 * JavaScript) can return an HTML shell without the solved-problems list, because
 * that list is populated by JavaScript after load.
 *
 * <p>To cope without adding a headless browser, this adapter FIRST looks for the
 * Next.js {@code <script id="__NEXT_DATA__" type="application/json">} blob that
 * Next.js embeds in the server response, and parses the solved-problems list out
 * of that JSON. Only if neither that JSON blob NOR any server-rendered problem
 * markup is present does it conclude the page is client-rendered and throw a
 * {@link ScrapeException} whose message explicitly states a headless browser may
 * be required. Adding Playwright/Selenium/HtmlUnit is intentionally NOT done here
 * because it changes the deployment footprint and needs explicit user approval
 * (see tasks.md notes and 04-api-integration-reference.md section 3).
 *
 * <h2>Metadata</h2>
 * Difficulty and tags are typically unavailable from the profile page, so they
 * are left {@code null} (rendered as "Not available" downstream, Requirement 8.2).
 *
 * <h2>Return contract</h2>
 * {@link #fetchRecent(String)} returns an empty list ONLY when the page parsed
 * successfully and the user legitimately has no solved problems. It never returns
 * {@code null}, and never silently returns empty to mask a parse failure.
 */
@Component
public class GfgAdapter implements SubmissionFetcher {

    private static final Logger log = LoggerFactory.getLogger(GfgAdapter.class);

    static final String DEFAULT_PROFILE_BASE = "https://www.geeksforgeeks.org/user";

    /** Jsoup connect/read timeout in milliseconds. */
    private static final int TIMEOUT_MS = 10_000;

    /** Browser-like User-Agent to reduce the chance of a bot block. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    /** The id of the Next.js JSON blob embedded in server responses. */
    private static final String NEXT_DATA_ID = "__NEXT_DATA__";

    /**
     * Candidate keys, under {@code props.pageProps} of the {@code __NEXT_DATA__}
     * payload, that may hold the solved-problems list. GFG's exact schema must be
     * verified against a live response (see 04-api-integration-reference.md); the
     * adapter tries each in order and uses the first array it finds. If none is
     * present the payload is considered structurally changed and a
     * {@link ScrapeException} is raised.
     */
    private static final String[] SOLVED_LIST_KEYS = {
            "solvedProblems", "problemsSolved", "solvedProblemsList",
            "submissions", "userSubmissionsInfo", "solvedQuestions"
    };

    /** Candidate keys for a problem's stable id/slug within a list element. */
    private static final String[] PROBLEM_ID_KEYS = {
            "slug", "problemId", "problem_id", "id", "questionSlug", "problemSlug"
    };

    /** Candidate keys for a problem's display name within a list element. */
    private static final String[] PROBLEM_NAME_KEYS = {
            "name", "problemName", "problem_name", "title", "questionName", "problemTitle"
    };

    /** Candidate keys for a problem's solved timestamp within a list element. */
    private static final String[] SOLVED_AT_KEYS = {
            "solvedAt", "solved_at", "timestamp", "submissionTime",
            "date", "solvedOn", "createdAt"
    };

    /**
     * CSS selectors for server-rendered problem markup. Used only as a
     * fallback signal to distinguish "page structure changed" from "page is
     * fully client-rendered": if there is no {@code __NEXT_DATA__} blob but one
     * of these matches, the page is server-rendered (structure changed →
     * {@link ScrapeException}); if neither matches, the page is client-rendered.
     */
    private static final String[] SERVER_MARKUP_SELECTORS = {
            "div.problemsSolved", ".problems_solved_container",
            "[class*=problemSolved]", "[class*=solvedProblem]"
    };

    private final ObjectMapper objectMapper;
    private final String profileBase;

    public GfgAdapter(
            @Value("${gfg.profile.base:" + DEFAULT_PROFILE_BASE + "}") String profileBase,
            ObjectMapper objectMapper) {
        // Normalise a trailing slash so profileUrl() builds a clean URL.
        this.profileBase = profileBase.endsWith("/")
                ? profileBase.substring(0, profileBase.length() - 1)
                : profileBase;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RawSubmission> fetchRecent(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("GFG username must not be blank");
        }
        String html = fetchHtml(profileUrl(username));
        return parse(html);
    }

    @Override
    public Platform platform() {
        return Platform.GFG;
    }

    /**
     * Builds the public profile URL for a username.
     */
    String profileUrl(String username) {
        return profileBase + "/" + username.trim() + "/";
    }

    /**
     * Fetches the raw profile HTML via Jsoup with a browser-like User-Agent and a
     * bounded timeout. Isolated from {@link #parse(String)} so parsing can be
     * unit-tested against saved fixtures with no network access (task 3.6).
     *
     * <p>Failure mapping:
     * <ul>
     *   <li>HTTP 429 → {@link RateLimitException} (trigger platform cooldown).</li>
     *   <li>Any other HTTP error (404 private/missing profile, 5xx, etc.) or
     *       transport/IO failure → {@link ScrapeException}.</li>
     * </ul>
     *
     * @param url the profile URL to GET
     * @return the response HTML, never {@code null}
     */
    String fetchHtml(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get();
            return doc.html();
        } catch (HttpStatusException e) {
            int status = e.getStatusCode();
            if (status == 429) {
                throw new RateLimitException(
                        "GFG rate-limited request for profile '" + url + "' (HTTP 429)", e);
            }
            throw new ScrapeException(
                    "GFG profile request failed for '" + url + "' (HTTP " + status + ")", e);
        } catch (IOException e) {
            throw new ScrapeException(
                    "GFG profile request failed (network/IO) for '" + url + "'", e);
        }
    }

    /**
     * Parses profile HTML into {@link RawSubmission}s.
     *
     * <p>Package-private and network-free so it can be unit-tested against saved
     * HTML fixtures (task 3.6). Strategy:
     * <ol>
     *   <li>Look for the Next.js {@code __NEXT_DATA__} JSON blob and, if present,
     *       parse the solved-problems list out of it.</li>
     *   <li>If the blob is absent, decide whether the page is merely
     *       server-rendered with changed markup (→ {@link ScrapeException}) or
     *       fully client-rendered (→ {@link ScrapeException} whose message calls
     *       out that a headless browser may be required).</li>
     * </ol>
     *
     * <p>Every failure path raises {@link ScrapeException}; this method never
     * throws a bare {@link RuntimeException} and never NPEs on missing fields. It
     * returns an empty list only when the JSON parsed cleanly and the user has no
     * solved problems.
     *
     * @param html the profile page HTML
     * @return parsed submissions, never {@code null}
     * @throws ScrapeException on any parse failure, missing blob, or structure change
     * @throws RateLimitException never (rate limits surface in {@link #fetchHtml})
     */
    List<RawSubmission> parse(String html) {
        if (html == null || html.isBlank()) {
            throw new ScrapeException("GFG profile HTML was empty");
        }

        final Document doc;
        try {
            doc = Jsoup.parse(html);
        } catch (RuntimeException e) {
            throw new ScrapeException("GFG profile HTML could not be parsed", e);
        }

        Element nextData = doc.getElementById(NEXT_DATA_ID);
        if (nextData == null) {
            // No embedded JSON. Distinguish "markup changed" from "client-rendered".
            if (hasServerRenderedProblemMarkup(doc)) {
                throw new ScrapeException(
                        "GFG profile page has no '" + NEXT_DATA_ID + "' JSON blob but does contain "
                        + "server-rendered problem markup — the page structure appears to have "
                        + "changed and the scraping selectors need updating (GFG_PARSE_FAILURE).");
            }
            throw new ScrapeException(
                    "GFG profile page appears to be client-rendered: no '" + NEXT_DATA_ID + "' JSON "
                    + "blob and no server-rendered problem markup were found. Jsoup cannot execute "
                    + "JavaScript, so a headless browser (e.g. Playwright/Selenium) may be required "
                    + "to scrape this page. Not adding one here — this needs explicit approval as it "
                    + "changes the deployment footprint (GFG_PARSE_FAILURE).");
        }

        String json = nextData.data();
        if (json == null || json.isBlank()) {
            // Fall back to text content in case the script body is exposed differently.
            json = nextData.html();
        }
        if (json == null || json.isBlank()) {
            throw new ScrapeException(
                    "GFG '" + NEXT_DATA_ID + "' script was present but empty (GFG_PARSE_FAILURE).");
        }

        return parseNextData(json);
    }

    /**
     * Parses the {@code __NEXT_DATA__} JSON string into {@link RawSubmission}s.
     * Navigates to {@code props.pageProps} and extracts the first recognised
     * solved-problems array. A cleanly-parsed payload with no solved problems
     * yields an empty list; anything structurally unexpected raises a
     * {@link ScrapeException}.
     */
    private List<RawSubmission> parseNextData(String json) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ScrapeException(
                    "GFG '" + NEXT_DATA_ID + "' blob was not valid JSON (GFG_PARSE_FAILURE).", e);
        }

        JsonNode pageProps = root.path("props").path("pageProps");
        if (pageProps.isMissingNode() || pageProps.isNull() || !pageProps.isObject()) {
            throw new ScrapeException(
                    "GFG '" + NEXT_DATA_ID + "' blob is missing the expected "
                    + "'props.pageProps' object — the page structure appears to have changed "
                    + "(GFG_PARSE_FAILURE).");
        }

        JsonNode solvedList = findSolvedList(pageProps);
        if (solvedList == null) {
            throw new ScrapeException(
                    "GFG '" + NEXT_DATA_ID + "' blob did not contain a recognised solved-problems "
                    + "list under 'props.pageProps' — the page structure appears to have changed "
                    + "(GFG_PARSE_FAILURE).");
        }

        // A present-but-empty list is the legitimate "no solved problems" case.
        // Invalid entries are skipped so one bad row does not discard partial valid
        // results. However, a recognised non-empty source yielding no valid rows is
        // a schema/parse failure, not evidence that the user solved nothing.
        List<RawSubmission> results = new ArrayList<>();
        for (JsonNode item : solvedList) {
            RawSubmission submission = toRawSubmission(item);
            if (submission != null) {
                results.add(submission);
            }
        }
        if (solvedList.size() > 0 && results.isEmpty()) {
            throw new ScrapeException(
                    "GFG '" + NEXT_DATA_ID + "' contained a recognised non-empty "
                    + "solved-problems list with " + solvedList.size() + " entries, but zero valid "
                    + "RawSubmission rows could be parsed. Each entry must provide a non-blank "
                    + "problem id/slug, problem name/title, and parseable solved timestamp — the "
                    + "upstream schema may have changed (GFG_PARSE_FAILURE).");
        }
        return results;
    }

    /**
     * Finds the solved-problems array under {@code pageProps}. Checks the known
     * candidate keys at the top level first, then one level deep (GFG sometimes
     * nests profile data under an intermediate object). Returns the first array
     * node found, or {@code null} if none is present.
     */
    private JsonNode findSolvedList(JsonNode pageProps) {
        // Direct candidate keys.
        JsonNode direct = firstArrayByKeys(pageProps);
        if (direct != null) {
            return direct;
        }
        // One level of nesting (e.g. pageProps.userInfo.solvedProblems).
        var fields = pageProps.fields();
        while (fields.hasNext()) {
            JsonNode child = fields.next().getValue();
            if (child != null && child.isObject()) {
                JsonNode nested = firstArrayByKeys(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * Returns the first array-valued node found among {@link #SOLVED_LIST_KEYS}
     * on the given object node, or {@code null} if none is an array.
     */
    private JsonNode firstArrayByKeys(JsonNode objectNode) {
        for (String key : SOLVED_LIST_KEYS) {
            JsonNode candidate = objectNode.get(key);
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Detects server-rendered problem markup, used to distinguish a
     * structure-change from full client-rendering when {@code __NEXT_DATA__} is
     * absent.
     */
    private boolean hasServerRenderedProblemMarkup(Document doc) {
        for (String selector : SERVER_MARKUP_SELECTORS) {
            try {
                if (!doc.select(selector).isEmpty()) {
                    return true;
                }
            } catch (RuntimeException e) {
                // A malformed selector should never happen for our constants, but
                // never let selector evaluation escape as a non-ScrapeException.
                log.debug("GFG server-markup selector '{}' failed to evaluate", selector, e);
            }
        }
        return false;
    }

    /**
     * Converts a single solved-list element into a {@link RawSubmission}, or
     * returns {@code null} when the element lacks the minimum required fields
     * (a problem id, a name, and a parseable solved timestamp) so a partially
     * malformed payload is skipped gracefully rather than aborting the whole
     * parse or fabricating data.
     *
     * <p>difficulty and tags are intentionally left {@code null} — GFG does not
     * expose them reliably (Requirement 8.2).
     */
    private RawSubmission toRawSubmission(JsonNode item) {
        if (item == null || !item.isObject()) {
            return null;
        }

        String problemId = firstText(item, PROBLEM_ID_KEYS);
        String problemName = firstText(item, PROBLEM_NAME_KEYS);
        Instant solvedAt = parseSolvedAt(item);

        if (problemId == null || problemName == null || solvedAt == null) {
            log.debug("Skipping GFG solved-list entry missing required field(s): {}", item);
            return null;
        }

        return RawSubmission.of(problemId, problemName, solvedAt);
    }

    /**
     * Parses a solved timestamp from an item, trying each candidate key. Accepts
     * either unix epoch seconds/millis (numeric or numeric-string) or an ISO-8601
     * instant string. Returns {@code null} if no candidate yields a parseable
     * timestamp.
     */
    private Instant parseSolvedAt(JsonNode item) {
        for (String key : SOLVED_AT_KEYS) {
            JsonNode node = item.get(key);
            if (node == null || node.isNull()) {
                continue;
            }
            Instant parsed = coerceInstant(node);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /**
     * Best-effort coercion of a JSON value to an {@link Instant}. Handles numeric
     * epoch seconds (10-digit) and epoch millis (13-digit), and ISO-8601 strings.
     */
    private Instant coerceInstant(JsonNode node) {
        try {
            if (node.isNumber()) {
                return epochToInstant(node.asLong());
            }
            String raw = node.asText();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            raw = raw.trim();
            // Numeric string → epoch.
            if (raw.chars().allMatch(Character::isDigit)) {
                return epochToInstant(Long.parseLong(raw));
            }
            // Otherwise attempt ISO-8601.
            return Instant.parse(raw);
        } catch (NumberFormatException | DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Interprets a numeric epoch as seconds or milliseconds based on magnitude.
     * Values with 13+ digits are treated as milliseconds.
     */
    private Instant epochToInstant(long epoch) {
        // ~1e12 is Sep 2001 in millis; anything at/above is treated as millis.
        if (epoch >= 1_000_000_000_000L) {
            return Instant.ofEpochMilli(epoch);
        }
        return Instant.ofEpochSecond(epoch);
    }

    /**
     * Returns the first non-blank text value found among {@code keys} on the
     * given object node, or {@code null} if none is present.
     */
    private static String firstText(JsonNode node, String[] keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                String s = value.asText();
                if (s != null && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        return null;
    }
}
