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
            String location,
            String employmentType,
            String careerLevel,
            String qualification,
            String postedText,
            boolean applied,
            Instant appliedAt
    ) {
        /** Build from an entity, keeping field mapping in one place. */
        static JobResponse from(JobListing listing, String postedByName, Instant appliedAt) {
            return new JobResponse(
                    listing.getId(), listing.getTitle(), listing.getCompany(), listing.getJobUrl(),
                    postedByName, listing.getCreatedAt(), listing.getExperienceRequired(),
                    listing.getDescription(), listing.getLocation(), listing.getEmploymentType(),
                    listing.getCareerLevel(), listing.getQualification(), listing.getPostedText(),
                    appliedAt != null, appliedAt);
        }
    }

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

    /**
     * @param extractionStatus FULL, LIMITED, NONE or ERROR — lets the UI flag portals that
     *                         need a dedicated extractor instead of showing an empty source
     */
    public record SourceResponse(
            Long id, String url, String label, Instant lastScrapedAt,
            String lastError, Instant createdAt, String addedByName,
            String adapter, int syncCursor, Instant sweepCompletedAt,
            String extractionStatus
    ) { }

    public record ScrapeResult(int newListings, String error) { }

    /**
     * Verdict on whether a career URL can be extracted, shown before the user commits
     * to adding it.
     *
     * @param level           FULL when a portal API adapter handles it, LIMITED when only
     *                        generic HTML scraping applies, NONE when nothing can be read,
     *                        ERROR when the check itself failed
     * @param adapter         adapter that claimed the URL, or null
     * @param detectedCompany company slug parsed out of the URL, useful as a label default
     * @param sampleJobCount  postings seen during the probe, or null when unknown
     * @param sampleTitles    a few real titles, so the user can see it actually works
     */
    public record SupportCheck(
            String level,
            String adapter,
            String detectedCompany,
            Integer sampleJobCount,
            List<String> sampleTitles,
            String message
    ) { }

    // --- Resume upload ---
    public record ResumeParseResponse(
            String suggestedRole,
            String detectedKeywords,
            String extractedText,
            Map<String, Integer> categoryScores,
            String fileName
    ) { }
}
