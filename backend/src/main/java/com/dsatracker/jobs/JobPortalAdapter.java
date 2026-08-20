package com.dsatracker.jobs;

import java.util.List;

/**
 * Pulls structured job data from a specific job portal's API.
 *
 * <p>Adapters exist because most career sites render their listings client-side:
 * the HTML served to a plain HTTP client contains no job data at all, so regex
 * scraping cannot recover titles, levels, locations, or per-job apply links.
 * An adapter talks to the same JSON API the portal's own frontend calls.
 *
 * <p>Fetching is chunked and resumable so a large portal (thousands of postings)
 * can be ingested across several scheduled runs without exceeding a request or
 * hosting timeout budget.
 */
public interface JobPortalAdapter {

    /** Short stable identifier persisted on the source, e.g. {@code "accenture"}. */
    String name();

    /** Whether this adapter can handle the given career page URL. */
    boolean supports(String url);

    /**
     * Fetch one chunk of postings.
     *
     * @param source     the configured source (provides the URL and thus locale)
     * @param startIndex zero-based offset into the portal's result set
     * @param chunkSize  how many postings to request
     * @return the chunk, plus whether the portal has no more results after it
     */
    Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception;

    /**
     * @param jobs      postings returned for the requested window
     * @param exhausted true when the portal returned fewer rows than requested,
     *                  meaning the sweep has reached the end of the result set
     */
    record Chunk(List<ScrapedJob> jobs, boolean exhausted) { }
}
