package com.dsatracker.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class JobDtos {
    private JobDtos() { }

    public record CreateRequest(String title, String company, String jobUrl) { }

    public record JobResponse(
            Long id,
            String title,
            String company,
            String jobUrl,
            String postedByName,
            Instant createdAt,
            Integer experienceRequired,
            String description,
            boolean applied,
            Instant appliedAt
    ) { }

    // --- Profiles ---
    public record CreateProfileRequest(String roleTitle, String keywords, String resumeText, String resumeFileName) { }

    public record ProfileResponse(
            Long id, String roleTitle, String keywords, String resumeText,
            String resumeFileName, Instant createdAt, List<JobResponse> matchedJobs,
            List<CompanyGroup> companyGroups
    ) {
        /** Backward-compatible constructor without companyGroups. */
        public ProfileResponse(Long id, String roleTitle, String keywords, String resumeText,
                               String resumeFileName, Instant createdAt, List<JobResponse> matchedJobs) {
            this(id, roleTitle, keywords, resumeText, resumeFileName, createdAt, matchedJobs, List.of());
        }
    }

    /** Jobs grouped by company for the My Roles view. */
    public record CompanyGroup(
            String company,
            Long sourceId,
            int totalJobs,
            List<JobResponse> jobs
    ) { }

    // --- Sources ---
    public record CreateSourceRequest(String url, String label) { }

    public record SourceResponse(
            Long id, String url, String label, Instant lastScrapedAt,
            String lastError, Instant createdAt, String addedByName
    ) { }

    public record ScrapeResult(int newListings, String error) { }

    // --- Resume upload ---
    public record ResumeParseResponse(
            String suggestedRole,
            String detectedKeywords,
            String extractedText,
            Map<String, Integer> categoryScores,
            String fileName
    ) { }
}
