package com.dsatracker.catalog;

import com.dsatracker.config.AsyncConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CatalogService {
    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final Pattern TITLE = Pattern.compile(
            "(?:[\"']?title[\"']?)\\s*:\\s*([\"'])(.*?)\\1", Pattern.DOTALL);
    private static final Pattern SLUG = Pattern.compile(
            "(?:[\"']?slug[\"']?)\\s*:\\s*([\"'])(.*?)\\1", Pattern.DOTALL);
    private static final Pattern DIFFICULTY = Pattern.compile("Easy|Medium|Hard");
    private static final int MAX_SOURCE_BYTES = 5_000_000;

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final URI questionsUri;
    private final URI roadmapsUri;
    private final boolean syncEnabled;
    private volatile CatalogModels.CatalogSnapshot snapshot;
    private volatile String snapshotHash;

    public CatalogService(ObjectMapper mapper,
            @Value("${catalog.questions-url}") String questionsUrl,
            @Value("${catalog.roadmaps-url}") String roadmapsUrl,
            @Value("${catalog.sync.enabled:true}") boolean syncEnabled) {
        this.mapper = mapper;
        this.questionsUri = requireHttps(questionsUrl);
        this.roadmapsUri = requireHttps(roadmapsUrl);
        this.syncEnabled = syncEnabled;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async(AsyncConfig.CATALOG_EXECUTOR)
    public void syncOnStartup() {
        if (syncEnabled) refresh();
    }

    @Scheduled(cron = "${catalog.sync.cron:0 0 3 * * MON}",
            zone = "${catalog.sync.zone:UTC}")
    @Async(AsyncConfig.CATALOG_EXECUTOR)
    public void syncEveryMonday() {
        if (syncEnabled) refresh();
    }

    public CatalogModels.CatalogSnapshot getSnapshot() {
        CatalogModels.CatalogSnapshot current = snapshot;
        if (current == null) {
            synchronized (this) {
                if (snapshot == null) refresh();
                current = snapshot;
            }
        }
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The patterns catalog is temporarily unavailable");
        }
        return current;
    }

    /** Returns only an already-loaded snapshot and never performs catalog HTTP. */
    public Optional<CatalogModels.CatalogSnapshot> loadedSnapshot() {
        return Optional.ofNullable(snapshot);
    }

    public synchronized void refresh() {
        Instant checkedAt = Instant.now();
        try {
            String questionsBody = fetch(questionsUri);
            String roadmapsBody = fetch(roadmapsUri);
            CatalogModels.SourceQuestions source = mapper.readValue(
                    questionsBody, CatalogModels.SourceQuestions.class);
            List<CatalogModels.Question> questions = source.data() == null
                    ? List.of() : source.data();
            List<CatalogModels.Roadmap> roadmaps = parseRoadmaps(roadmapsBody);
            validate(questions, roadmaps);

            String hash = sha256(questionsBody + "\n--ROADMAPS--\n" + roadmapsBody);
            Instant changedAt = snapshot == null || !hash.equals(snapshotHash)
                    ? checkedAt : snapshot.lastChangedAt();
            snapshot = new CatalogModels.CatalogSnapshot(
                    checkedAt, parseSourceTime(source.updated()), changedAt, true,
                    List.copyOf(questions), List.copyOf(roadmaps),
                    "Leetcode Patterns by Sean Prashad",
                    "https://seanprashad.com/leetcode-patterns/",
                    "CC BY-NC 4.0", "https://creativecommons.org/licenses/by-nc/4.0/");
            snapshotHash = hash;
            log.info("Patterns catalog sync succeeded: {} questions, {} roadmaps, changed={}",
                    questions.size(), roadmaps.size(), changedAt.equals(checkedAt));
        } catch (Exception ex) {
            log.warn("Patterns catalog sync failed; retaining last good snapshot", ex);
            if (snapshot != null) {
                snapshot = new CatalogModels.CatalogSnapshot(
                        checkedAt, snapshot.sourceUpdatedAt(), snapshot.lastChangedAt(), false,
                        snapshot.questions(), snapshot.roadmaps(), snapshot.sourceName(),
                        snapshot.sourceUrl(), snapshot.licenseName(), snapshot.licenseUrl());
            }
        }
    }

    private String fetch(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/plain;q=0.9")
                .header("User-Agent", "DSA-Progress-Tracker/1.0")
                .GET().build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("source returned HTTP " + response.statusCode());
        }
        String body = response.body();
        if (body == null || body.isBlank() || body.length() > MAX_SOURCE_BYTES) {
            throw new IllegalStateException("source body was empty or too large");
        }
        return body;
    }

    private static List<CatalogModels.Roadmap> parseRoadmaps(String source) {
        return List.of(
                parseRoadmap(source, "beginnerRoadmap", "beginner", "Beginner Roadmap"),
                parseRoadmap(source, "experiencedRoadmap", "experienced", "Experienced Roadmap"));
    }

    private static CatalogModels.Roadmap parseRoadmap(
            String source, String variable, String id, String name) {
        Matcher declaration = Pattern.compile(
                "export\\s+const\\s+" + Pattern.quote(variable) + "\\b").matcher(source);
        if (!declaration.find()) throw new IllegalArgumentException("roadmap marker missing: " + id);
        String roadmapObject = extractDelimited(source, declaration.end(), '{', '}');
        int phasesIndex = propertyIndex(roadmapObject, "phases");
        if (phasesIndex < 0) throw new IllegalArgumentException("phases missing: " + id);
        String phasesArray = extractDelimited(roadmapObject, phasesIndex, '[', ']');
        List<String> phaseObjects = splitTopLevelObjects(phasesArray);
        List<CatalogModels.RoadmapPhase> phases = new ArrayList<>();
        for (String phase : phaseObjects) {
            Matcher title = TITLE.matcher(phase);
            if (!title.find()) continue;
            int questionsIndex = propertyIndex(phase, "questions");
            if (questionsIndex < 0) continue;
            String questionArray = extractDelimited(phase, questionsIndex, '[', ']');
            Matcher slugs = SLUG.matcher(questionArray);
            List<String> questionSlugs = new ArrayList<>();
            while (slugs.find()) questionSlugs.add(slugs.group(2));
            if (!questionSlugs.isEmpty()) {
                phases.add(new CatalogModels.RoadmapPhase(
                        title.group(2), phases.size() + 1, List.copyOf(questionSlugs)));
            }
        }
        return new CatalogModels.Roadmap(id, name, List.copyOf(phases));
    }

    private static int propertyIndex(String source, String property) {
        Matcher matcher = Pattern.compile(
                "(?:[\"']?" + Pattern.quote(property) + "[\"']?)\\s*:").matcher(source);
        return matcher.find() ? matcher.end() : -1;
    }

    private static String extractDelimited(String text, int from, char open, char close) {
        int start = text.indexOf(open, from);
        if (start < 0) throw new IllegalArgumentException("opening delimiter missing");
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (lineComment) {
                if (c == '\n' || c == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        throw new IllegalArgumentException("closing delimiter missing");
    }

    private static List<String> splitTopLevelObjects(String array) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        char quote = 0;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            char next = i + 1 < array.length() ? array.charAt(i + 1) : 0;
            if (lineComment) {
                if (c == '\n' || c == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '{') {
                if (depth++ == 0) start = i;
            } else if (c == '}' && --depth == 0 && start >= 0) {
                objects.add(array.substring(start, i + 1));
                start = -1;
            }
        }
        return objects;
    }

    private static void validate(List<CatalogModels.Question> questions,
                                 List<CatalogModels.Roadmap> roadmaps) {
        if (questions.size() < 100 || roadmaps.size() != 2) {
            throw new IllegalArgumentException("catalog source failed completeness validation");
        }

        java.util.Set<String> slugs = new java.util.HashSet<>();
        for (CatalogModels.Question question : questions) {
            if (question == null || question.id() <= 0 || blank(question.title())
                    || blank(question.slug()) || blank(question.difficulty())
                    || !DIFFICULTY.matcher(question.difficulty()).matches()
                    || question.patterns() == null || question.patterns().isEmpty()
                    || question.patterns().stream().anyMatch(CatalogService::blank)
                    || question.companies() == null) {
                throw new IllegalArgumentException("catalog contains an invalid question");
            }
            if (!slugs.add(question.slug())) {
                throw new IllegalArgumentException("catalog contains duplicate question slugs");
            }
            for (CatalogModels.Company company : question.companies()) {
                if (company == null || blank(company.name()) || blank(company.slug())
                        || company.frequency() < 0) {
                    throw new IllegalArgumentException("catalog contains invalid company metadata");
                }
            }
        }

        java.util.Set<String> roadmapIds = new java.util.HashSet<>();
        for (CatalogModels.Roadmap roadmap : roadmaps) {
            if (roadmap == null || blank(roadmap.id()) || blank(roadmap.name())
                    || roadmap.phases() == null || roadmap.phases().isEmpty()
                    || !roadmapIds.add(roadmap.id())) {
                throw new IllegalArgumentException("catalog contains an invalid roadmap");
            }
            java.util.Set<String> roadmapSlugs = new java.util.HashSet<>();
            for (CatalogModels.RoadmapPhase phase : roadmap.phases()) {
                if (phase == null || blank(phase.title()) || phase.position() <= 0
                        || phase.questionSlugs() == null || phase.questionSlugs().isEmpty()) {
                    throw new IllegalArgumentException("catalog contains an invalid roadmap phase");
                }
                for (String slug : phase.questionSlugs()) {
                    if (!slugs.contains(slug) || !roadmapSlugs.add(slug)) {
                        throw new IllegalArgumentException(
                                "roadmap contains an unknown or repeated question slug");
                    }
                }
            }
        }
        if (!roadmapIds.equals(java.util.Set.of("beginner", "experienced"))) {
            throw new IllegalArgumentException("catalog roadmaps are incomplete");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Instant parseSourceTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        }
    }

    private static URI requireHttps(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Catalog source URLs must use HTTPS");
        }
        return uri;
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
