package com.dsatracker.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/github/captures")
public class GitHubCaptureController {
    private static final int MAX_BODY_CHARS = 300_000;
    private final GitHubCaptureService service;
    private final ObjectMapper mapper;

    public GitHubCaptureController(GitHubCaptureService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<?> capture(
            @RequestHeader(value = "X-DSA-Extension-Token", required = false) String token,
            HttpServletRequest request) {
        GitHubConnection connection = service.authenticate(token);
        if (connection == null) return unauthorized();
        GitHubDtos.CaptureCommand command;
        try {
            command = parse(mapper.readTree(readBody(request)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "Invalid JSON payload"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.capture(connection.getUserId(), command));
    }

    private static String readBody(HttpServletRequest request) throws Exception {
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[8192];
        var reader = request.getReader();
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            if (body.length() + read > MAX_BODY_CHARS) invalid("Payload is too large");
            body.append(buffer, 0, read);
        }
        return body.toString();
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Unauthorized"));
    }

    private static GitHubDtos.CaptureCommand parse(JsonNode payload) {
        if (payload == null || !payload.isObject()) invalid("Payload must be a JSON object");
        String platformValue = required(payload, "platform", 20);
        GitHubPlatform platform;
        try {
            platform = GitHubPlatform.valueOf(platformValue);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "platform must be LEETCODE, CODEFORCES, or GEEKSFORGEEKS");
        }
        String problemId = required(payload, "problemId", 150);
        String problemName = required(payload, "problemName", 255);
        String problemUrl = required(payload, "problemUrl", 2048);
        validateUrl(platform, problemUrl);
        String language = required(payload, "language", 100);
        String source = optionalText(payload, "source");
        if (source == null || source.isBlank()) invalid("source is required");
        if (source.length() > 250_000) invalid("source exceeds 250000 characters");
        String sourceHash = required(payload, "sourceHash", 64);
        if (!sourceHash.matches("(?i)[0-9a-f]{64}")) {
            invalid("sourceHash must be a SHA-256 hex digest");
        }
        String difficulty = optionalText(payload, "difficulty");
        if (difficulty != null) {
            difficulty = difficulty.trim();
            if (difficulty.isBlank() || difficulty.length() > 30) {
                invalid("difficulty must be nonblank and at most 30 characters");
            }
        }
        List<String> tags = parseTags(payload.get("tags"));
        String solvedAtValue = required(payload, "solvedAt", 100);
        Instant solvedAt;
        try {
            solvedAt = Instant.parse(solvedAtValue);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("solvedAt must be an ISO-8601 instant");
        }
        return new GitHubDtos.CaptureCommand(platform, problemId, problemName, problemUrl,
                language, source, sourceHash, difficulty, tags, solvedAt);
    }

    private static String required(JsonNode payload, String field, int maxLength) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            invalid(field + " is required");
        }
        String text = value.asText().trim();
        if (text.length() > maxLength) invalid(field + " is too long");
        return text;
    }

    private static String optionalText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) invalid(field + " must be a string");
        return value.asText();
    }

    private static List<String> parseTags(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isArray()) invalid("tags must be an array");
        if (value.size() > 20) invalid("tags may contain at most 20 items");
        List<String> tags = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().trim().length() > 64) {
                invalid("each tag must be nonblank and at most 64 characters");
            }
            tags.add(item.asText().trim());
        }
        return List.copyOf(tags);
    }

    private static void validateUrl(GitHubPlatform platform, String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("problemUrl must be a valid HTTPS URL");
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || uri.getUserInfo() != null) {
            invalid("problemUrl must be a valid HTTPS URL");
        }
        Set<String> allowed = switch (platform) {
            case LEETCODE -> Set.of("leetcode.com", "www.leetcode.com");
            case CODEFORCES -> Set.of("codeforces.com", "www.codeforces.com");
            case GEEKSFORGEEKS -> Set.of("geeksforgeeks.org", "www.geeksforgeeks.org",
                    "practice.geeksforgeeks.org");
        };
        if (!allowed.contains(host.toLowerCase(Locale.ROOT))) {
            invalid("problemUrl host does not match platform");
        }
    }

    private static void invalid(String message) { throw new IllegalArgumentException(message); }
}
