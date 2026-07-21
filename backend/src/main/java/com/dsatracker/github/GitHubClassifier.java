package com.dsatracker.github;

import com.dsatracker.catalog.CatalogModels;
import com.dsatracker.catalog.CatalogService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class GitHubClassifier {
    private static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("array", "arrays"), Map.entry("arrays", "arrays"),
            Map.entry("array-hashing", "arrays"), Map.entry("arrays-hashing", "arrays"),
            Map.entry("string", "strings"), Map.entry("strings", "strings"),
            Map.entry("linked-list", "linked-list"), Map.entry("linked-lists", "linked-list"),
            Map.entry("dynamic-programming", "dynamic-programming"),
            Map.entry("dp", "dynamic-programming"), Map.entry("recursion", "recursion"));
    private final CatalogService catalog;

    public GitHubClassifier(CatalogService catalog) { this.catalog = catalog; }

    public String classify(GitHubPlatform platform, String problemUrl, List<String> tags) {
        String candidate = null;
        if (platform == GitHubPlatform.LEETCODE) {
            String problemSlug = leetCodeSlug(problemUrl, null, null);
            candidate = catalog.loadedSnapshot().stream()
                    .map(CatalogModels.CatalogSnapshot::questions)
                    .flatMap(List::stream)
                    .filter(question -> problemSlug.equals(question.slug()))
                    .map(CatalogModels.Question::patterns)
                    .filter(patterns -> patterns != null && !patterns.isEmpty())
                    .map(patterns -> patterns.get(0))
                    .findFirst().orElse(null);
        }
        if (candidate == null && tags != null && !tags.isEmpty()) candidate = tags.get(0);
        String safe = safeSlug(candidate);
        return CANONICAL.getOrDefault(safe, safe);
    }

    public String path(GitHubSolutionCapture capture) {
        String problemSlug = capture.getPlatform() == GitHubPlatform.LEETCODE
                ? leetCodeSlug(capture.getProblemUrl(), capture.getProblemId(),
                        capture.getProblemName())
                : safeSlug(capture.getProblemName());
        String file = safeSlug(capture.getPlatform().name().toLowerCase(Locale.ROOT))
                + "-" + capture.getId() + ".md";
        return safeSlug(capture.getPatternSlug()) + "/" + problemSlug + "/" + file;
    }

    private static String leetCodeSlug(String problemUrl, String problemId, String problemName) {
        try {
            String[] segments = URI.create(problemUrl).getPath().split("/");
            for (int index = 0; index + 1 < segments.length; index++) {
                if ("problems".equals(segments[index]) && !segments[index + 1].isBlank()) {
                    return safeSlug(segments[index + 1]);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Capture validation normally guarantees a URI; retain a safe fallback for old rows.
        }
        String id = safeSlug(problemId);
        return "uncategorized".equals(id) ? safeSlug(problemName) : id;
    }

    public static String safeSlug(String value) {
        if (value == null || value.isBlank()) return "uncategorized";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")) {
            return "uncategorized";
        }
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100)
                .replaceAll("-+$", "");
    }
}
