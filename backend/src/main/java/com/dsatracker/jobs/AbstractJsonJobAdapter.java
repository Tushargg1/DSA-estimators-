package com.dsatracker.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared plumbing for adapters that read a JSON job board over HTTP.
 *
 * <p>Applicant tracking systems (Greenhouse, Lever, Ashby, ...) host boards for many
 * companies behind one API whose only variable is a company slug in the path. So a
 * single adapter per ATS covers every company on it — subclasses supply the URL
 * pattern and the field mapping, and inherit fetching, HTML cleanup and slug parsing.
 */
abstract class AbstractJsonJobAdapter implements JobPortalAdapter {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BODY_CHARS = 8 * 1024 * 1024;

    protected final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(TIMEOUT)
            .build();

    /** Pattern whose first group captures the company slug from a board URL. */
    protected abstract Pattern slugPattern();

    /** Extract the company slug, or null when the URL isn't for this ATS. */
    protected String slugFrom(String url) {
        if (url == null) return null;
        Matcher matcher = slugPattern().matcher(url);
        if (!matcher.find()) return null;
        String slug = matcher.group(1);
        return slug == null || slug.isBlank() ? null : slug.toLowerCase();
    }

    @Override
    public boolean supports(String url) {
        return slugFrom(url) != null;
    }

    protected JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (compatible; DSA-Tracker-JobBot/1.0)")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new IOException("Board not found (HTTP 404) — check the company slug in the URL");
        }
        if (response.statusCode() >= 400) {
            throw new IOException("Job board API returned HTTP " + response.statusCode());
        }
        String body = response.body();
        if (body.length() > MAX_BODY_CHARS) body = body.substring(0, MAX_BODY_CHARS);
        return mapper.readTree(body);
    }

    // --- field helpers ---

    protected static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String raw = value.asText("").trim();
        return raw.isEmpty() ? null : raw;
    }

    /** Read a nested field, e.g. {@code nested(job, "location", "name")}. */
    protected static String nested(JsonNode node, String parent, String child) {
        if (node == null) return null;
        JsonNode outer = node.get(parent);
        return outer == null || outer.isNull() ? null : text(outer, child);
    }

    /** Join an array-of-objects field's named property, e.g. departments[].name. */
    protected static String joinObjectArray(JsonNode node, String field, String property) {
        if (node == null) return null;
        JsonNode array = node.get(field);
        if (array == null || !array.isArray()) return null;
        List<String> parts = new ArrayList<>();
        for (JsonNode item : array) {
            String value = text(item, property);
            if (value != null && !parts.contains(value)) parts.add(value);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** Join a plain string array field. */
    protected static String joinStringArray(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode array = node.get(field);
        if (array == null || !array.isArray()) return null;
        List<String> parts = new ArrayList<>();
        for (JsonNode item : array) {
            String value = item.asText("").trim();
            if (!value.isEmpty() && !parts.contains(value)) parts.add(value);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    protected static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /**
     * Convert a description field to plain text suitable for keyword matching.
     *
     * <p>Entities are decoded before tags are stripped: some boards (Greenhouse)
     * return HTML that is itself entity-escaped, so stripping first would leave the
     * markup intact as literal text.
     */
    protected static String toPlainText(String value) {
        if (value == null || value.isBlank()) return null;
        String text = decodeEntities(value)
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ");
        text = decodeEntities(text).replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private static String decodeEntities(String value) {
        String text = value
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&mdash;", "-").replace("&ndash;", "-")
                .replace("&amp;", "&"); // last, so "&amp;lt;" resolves correctly
        Matcher matcher = NUMERIC_ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            try {
                int radix = matcher.group(1).isEmpty() ? 10 : 16;
                replacement = String.valueOf((char) Integer.parseInt(matcher.group(2), radix));
            } catch (NumberFormatException e) {
                replacement = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Render an ISO-8601 or epoch-millis timestamp as a relative "Posted ..." label. */
    protected static String relativePosted(String isoOrEpochMillis) {
        if (isoOrEpochMillis == null || isoOrEpochMillis.isBlank()) return null;
        Instant when = parseInstant(isoOrEpochMillis);
        if (when == null) return null;
        long days = ChronoUnit.DAYS.between(when, Instant.now());
        if (days < 0) return null;
        if (days == 0) return "Posted within last 24 hours";
        if (days == 1) return "Posted 1 day ago";
        if (days < 31) return "Posted " + days + " days ago";
        return "Posted more than 1 month ago";
    }

    private static Instant parseInstant(String value) {
        try {
            // Lever publishes epoch millis; the others use ISO-8601.
            if (value.matches("\\d{10,}")) return Instant.ofEpochMilli(Long.parseLong(value));
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (Exception first) {
            try {
                return Instant.parse(value);
            } catch (Exception second) {
                return null;
            }
        }
    }
}
