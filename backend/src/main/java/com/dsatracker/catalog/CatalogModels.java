package com.dsatracker.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public final class CatalogModels {
    private CatalogModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Company(String name, String slug, int frequency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(int id, String title, String slug,
                           @JsonProperty("pattern") List<String> patterns,
                           String difficulty, boolean premium, List<Company> companies) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceQuestions(String updated, List<Question> data) {
    }

    public record RoadmapPhase(String title, int position, List<String> questionSlugs) {
    }

    public record Roadmap(String id, String name, List<RoadmapPhase> phases) {
    }

    public record CatalogSnapshot(Instant checkedAt, Instant sourceUpdatedAt,
                                  Instant lastChangedAt, boolean syncHealthy,
                                  List<Question> questions, List<Roadmap> roadmaps,
                                  String sourceName, String sourceUrl,
                                  String licenseName, String licenseUrl) {
    }
}
