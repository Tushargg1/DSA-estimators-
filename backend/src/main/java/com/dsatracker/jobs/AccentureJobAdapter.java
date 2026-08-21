package com.dsatracker.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Accenture postings from the same endpoint their careers site calls:
 * {@code POST /api/accenture/elastic/findjobs} (multipart form body, JSON reply).
 *
 * <p>The public careers page is client-rendered — its HTML contains no postings —
 * so this API is the only way to obtain real titles, career levels, locations and
 * per-job apply links.
 *
 * <p>Two quirks of the API shape drive the design here:
 * <ul>
 *   <li>{@code jobDetailUrl} embeds a {@code {0}} placeholder that must be replaced
 *       with the locale segment (e.g. {@code in-en}) to form a working link.</li>
 *   <li>Results are relevance-ordered, not date-ordered, and {@code totalHits} is a
 *       capped estimate ({@code overMaxHits}). So callers must sweep the whole range
 *       and stop only on a short chunk — never trust the total, and never stop early
 *       on "already seen" ids.</li>
 * </ul>
 */
@Component
public class AccentureJobAdapter implements JobPortalAdapter {
    private static final Logger log = LoggerFactory.getLogger(AccentureJobAdapter.class);

    private static final String ENDPOINT = "https://www.accenture.com/api/accenture/elastic/findjobs";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern LOCALE_PATTERN = Pattern.compile("accenture\\.com/([a-z]{2}-[a-z]{2})(/|$)");
    private static final String DEFAULT_LOCALE = "us-en";

    /**
     * The API wants a country display name, which is not derivable from the locale
     * code alone. Unmapped locales simply omit the filter and search globally.
     */
    private static final Map<String, String> LOCALE_COUNTRY = Map.ofEntries(
            Map.entry("in-en", "India"),
            Map.entry("us-en", "United States"),
            Map.entry("gb-en", "United Kingdom"),
            Map.entry("ca-en", "Canada"),
            Map.entry("au-en", "Australia"),
            Map.entry("ie-en", "Ireland"),
            Map.entry("de-de", "Germany"),
            Map.entry("fr-fr", "France"),
            Map.entry("es-es", "Spain"),
            Map.entry("it-it", "Italy"),
            Map.entry("jp-ja", "Japan"),
            Map.entry("sg-en", "Singapore"),
            Map.entry("ph-en", "Philippines"),
            Map.entry("br-pt", "Brazil"),
            Map.entry("mx-es", "Mexico"),
            Map.entry("za-en", "South Africa"),
            Map.entry("pl-pl", "Poland"),
            Map.entry("nl-en", "Netherlands")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(TIMEOUT)
            .build();

    @Override
    public String name() {
        return "accenture";
    }

    @Override
    public boolean supports(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("accenture.com") && lower.contains("career");
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        // Locations are wanted for India specifically, regardless of which Accenture
        // locale page the source URL happens to point at.
        String locale = "in-en";
        String country = LOCALE_COUNTRY.get(locale);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("startIndex", String.valueOf(startIndex));
        fields.put("maxResultSize", String.valueOf(chunkSize));
        fields.put("jobKeyword", "");
        if (country != null) fields.put("jobCountry", country);
        fields.put("jobLanguage", "en");
        fields.put("countrySite", locale);
        fields.put("sortBy", "2");
        fields.put("totalHits", "true");
        fields.put("jobFilters", "[]");

        String boundary = "----DsaTrackerBoundary" + System.nanoTime();
        byte[] body = multipartBody(fields, boundary);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (compatible; DSA-Tracker-JobBot/1.0)")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Accenture API returned HTTP " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new IOException("Unexpected Accenture API response: missing data array");
        }

        List<ScrapedJob> jobs = new ArrayList<>();
        for (JsonNode node : data) {
            ScrapedJob job = mapJob(node, locale);
            if (job != null) jobs.add(job);
        }

        // Relevance ordering plus a capped totalHits mean the only trustworthy
        // end-of-results signal is the portal returning fewer rows than asked for.
        boolean exhausted = data.size() < chunkSize;
        log.debug("Accenture chunk locale={} start={} size={} -> {} jobs, exhausted={}",
                locale, startIndex, chunkSize, jobs.size(), exhausted);
        return new Chunk(jobs, exhausted);
    }

    private ScrapedJob mapJob(JsonNode node, String locale) {
        String title = text(node, "title");
        String rawUrl = text(node, "jobDetailUrl");
        if (title == null || rawUrl == null) return null;

        // jobDetailUrl arrives as ".../{0}/careers/jobdetails?id=..." — the placeholder
        // must become the locale segment or the link 404s.
        String url = rawUrl.replace("{0}", locale);

        String description = firstNonBlank(
                text(node, "jobDescriptionClean"),
                text(node, "azureopenaiSummary"),
                text(node, "jobDescription"));

        // Fold the portal's skill lists into the description so resume keyword
        // matching can see them even when they're absent from the prose.
        String skills = joinArray(node, "skill", "mustHaveSkills", "goodToHaveSkills");
        if (skills != null) {
            description = description == null ? skills : description + " " + skills;
        }

        return new ScrapedJob(
                text(node, "requisitionId"),
                title,
                url,
                description,
                joinArray(node, "location"),
                text(node, "jobScheduleDescription"),
                text(node, "jobTypeDescription"),
                firstNonBlank(joinArray(node, "education"), text(node, "qualificationClean")),
                text(node, "postedDateText"),
                parseYears(node)
        );
    }

    /** Prefer the portal's own years-of-experience value; fall back to null. */
    private Integer parseYears(JsonNode node) {
        JsonNode years = node.get("yearsOfExperience");
        if (years == null || years.isNull()) return null;
        if (years.isInt()) {
            int value = years.asInt();
            return value >= 0 && value <= 30 ? value : null;
        }
        String raw = years.asText("");
        Matcher matcher = Pattern.compile("(\\d{1,2})").matcher(raw);
        if (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value >= 0 && value <= 30) return value;
            } catch (NumberFormatException ignored) { /* fall through to null */ }
        }
        return null;
    }

    static String localeFrom(String url) {
        if (url == null) return DEFAULT_LOCALE;
        Matcher matcher = LOCALE_PATTERN.matcher(url.toLowerCase());
        return matcher.find() ? matcher.group(1) : DEFAULT_LOCALE;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String raw = value.asText("").trim();
        return raw.isEmpty() ? null : raw;
    }

    /** Flatten one or more string-array fields into a comma-joined value. */
    private static String joinArray(JsonNode node, String... fields) {
        List<String> parts = new ArrayList<>();
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) continue;
            if (value.isArray()) {
                for (JsonNode item : value) {
                    String raw = item.asText("").trim();
                    if (!raw.isEmpty() && !parts.contains(raw)) parts.add(raw);
                }
            } else {
                String raw = value.asText("").trim();
                if (!raw.isEmpty() && !parts.contains(raw)) parts.add(raw);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static byte[] multipartBody(Map<String, String> fields, String boundary) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(field.getValue().getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
