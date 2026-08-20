package com.dsatracker.jobs;

/**
 * A normalized job posting produced by a {@link JobPortalAdapter}.
 *
 * <p>Every field except {@code url} may be null: portals expose different subsets
 * of metadata, and the generic HTML scraper can only fill in a title at best.
 *
 * @param externalId      portal's stable job id, used for dedup (Accenture: requisitionId)
 * @param title           role name, e.g. "Software Development Engineer"
 * @param url             direct apply/detail link for this specific job
 * @param description     plain-text description, used for resume keyword matching
 * @param location        e.g. "Noida" or "Bengaluru, Mumbai"
 * @param employmentType  e.g. "Full time"
 * @param careerLevel     e.g. "Mid-Level", "Early Career", "Senior Level"
 * @param qualification   e.g. "Bachelor Degree, Graduate Degree/PhD"
 * @param postedText      e.g. "Posted 1 day ago"
 * @param yearsExperience minimum years required, or null when unstated
 */
public record ScrapedJob(
        String externalId,
        String title,
        String url,
        String description,
        String location,
        String employmentType,
        String careerLevel,
        String qualification,
        String postedText,
        Integer yearsExperience
) {
    /** Convenience factory for the generic HTML scraper, which only finds a title and URL. */
    static ScrapedJob basic(String url, String title, String description, Integer yearsExperience) {
        return new ScrapedJob(null, title, url, description, null, null, null, null, null, yearsExperience);
    }
}
