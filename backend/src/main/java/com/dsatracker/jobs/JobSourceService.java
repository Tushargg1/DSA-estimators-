package com.dsatracker.jobs;

import com.dsatracker.config.AsyncConfig;
import com.dsatracker.model.User;
import com.dsatracker.repository.UserRepository;
import com.dsatracker.web.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages job source URLs and performs basic HTML scraping to discover job links.
 *
 * <p>Scraping is intentionally simple: fetch the page, find all anchor hrefs that
 * look like job application links (contain keywords like /job/, /career/, /apply/, etc.),
 * and insert them as new listings. No JavaScript rendering or headless browser is used.
 */
@Service
public class JobSourceService {
    private static final Logger log = LoggerFactory.getLogger(JobSourceService.class);
    private static final int MAX_SOURCES = 20;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024; // 2 MB

    private static final Pattern HREF_PATTERN = Pattern.compile(
            "href\\s*=\\s*[\"']([^\"']{10,2048})[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOB_URL_PATTERN = Pattern.compile(
            "/job[s]?/|/career[s]?/|/apply|/position[s]?/|/opening[s]?/|/vacanc",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            ">([^<]{3,200})</a>", Pattern.CASE_INSENSITIVE);

    /**
     * Per-chunk request size, and how many chunks one run may fetch. Together these
     * bound a single run's work (600 postings) so a large portal is ingested across
     * several scheduled runs rather than one long request that a free-tier host kills.
     */
    private static final int CHUNK_SIZE = 100;
    private static final int MAX_CHUNKS_PER_RUN = 6;
    /**
     * A user clicking "Scrape now" waits on the response, so interactive runs fetch a
     * single chunk and return. Working through a large board is the scheduler's job.
     */
    private static final int INTERACTIVE_CHUNKS = 1;

    /**
     * Upper bound on chunks in one background sweep, guarding against a portal that keeps
     * returning full pages forever. At {@link #CHUNK_SIZE} per chunk this covers boards far
     * larger than any we target.
     */
    private static final int MAX_CHUNKS_PER_SWEEP = 300;
    /** Brief pause between chunks so a full sweep doesn't hammer the portal. */
    private static final long CHUNK_PAUSE_MILLIS = 400L;

    private final JobSourceRepository sources;
    private final JobListingRepository listings;
    private final JobApplicationRepository applications;
    private final UserRepository users;
    private final List<JobPortalAdapter> adapters;
    private final JobIngestWriter writer;
    /**
     * Self-reference used to invoke {@link #ingestRemainderInBackground(Long)} through the
     * Spring proxy. Calling an {@code @Async} method directly on {@code this} would run it
     * inline on the caller's thread, defeating the point.
     */
    private final ObjectProvider<JobSourceService> self;

    public JobSourceService(JobSourceRepository sources,
                            JobListingRepository listings,
                            JobApplicationRepository applications,
                            UserRepository users,
                            List<JobPortalAdapter> adapters,
                            JobIngestWriter writer,
                            ObjectProvider<JobSourceService> self) {
        this.sources = sources;
        this.listings = listings;
        this.applications = applications;
        this.users = users;
        this.adapters = adapters;
        this.writer = writer;
        this.self = self;
    }

    /**
     * Keep pulling a board until the portal reports no more results.
     *
     * <p>Runs after the triggering request has already responded, which is what makes a
     * multi-thousand-posting board possible: the work is no longer bounded by how long a
     * client will wait. Progress is checkpointed per chunk, so if the process stops midway
     * the scheduled sweep resumes from the stored cursor rather than starting over.
     */
    @Async(AsyncConfig.JOB_INGEST_EXECUTOR)
    public void ingestRemainderInBackground(Long sourceId) {
        for (int chunk = 0; chunk < MAX_CHUNKS_PER_SWEEP; chunk++) {
            JobSource source = sources.findById(sourceId).orElse(null);
            // Stop if the source was removed, or if the cursor reset means it finished.
            if (source == null || source.getSyncCursor() <= 0) return;

            JobPortalAdapter adapter = findAdapter(source.getUrl());
            if (adapter == null) return; // Generic scrapes complete in a single pass.

            JobDtos.ScrapeResult result = syncViaAdapter(source, adapter, 1);
            if (result.error() != null) {
                log.warn("[BackgroundSweep] Stopping source {} after error: {}",
                        sourceId, result.error());
                return;
            }
            try {
                Thread.sleep(CHUNK_PAUSE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("[BackgroundSweep] Source {} hit the {}-chunk ceiling; the scheduled sweep "
                + "will continue it", sourceId, MAX_CHUNKS_PER_SWEEP);
    }

    @Transactional(readOnly = true)
    public List<JobDtos.SourceResponse> listSources() {
        List<JobSource> allSources = sources.findAllByOrderByCreatedAtDesc();
        if (allSources.isEmpty()) return List.of();
        Collection<Long> userIds = allSources.stream().map(JobSource::getAddedBy).collect(Collectors.toSet());
        Map<Long, String> names = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        return allSources.stream()
                .map(source -> toResponse(source, names.getOrDefault(source.getAddedBy(), "Member")))
                .toList();
    }

    /**
     * Register a career page and immediately try to extract from it.
     *
     * <p>Scraping right away means the user finds out straight away whether the site
     * works, instead of adding a source that quietly returns nothing. The outcome is
     * stored as an extraction status so unsupported portals stay visibly flagged until
     * an adapter exists for them.
     *
     * <p>Not {@code @Transactional}: the fetch is an external HTTP call and must not run
     * inside a transaction. The row is saved first in its own transaction, then scraped.
     */
    public JobDtos.SourceResponse addSource(Long userId, JobDtos.CreateSourceRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        String url = validateUrl(request == null ? null : request.url(), errors);
        String label = request == null ? null : request.label();
        if (label != null) label = label.trim().isEmpty() ? null : label.trim();
        if (label != null && label.length() > 200) label = label.substring(0, 200);
        if (!errors.isEmpty()) throw new ValidationException(errors);

        List<JobSource> existing = sources.findAllByOrderByCreatedAtDesc();
        if (existing.size() >= MAX_SOURCES) {
            errors.put("url", "Maximum of " + MAX_SOURCES + " sources reached.");
            throw new ValidationException(errors);
        }
        // Adding the same board twice produces duplicate cards and duplicate sweeps.
        if (existing.stream().anyMatch(source -> source.getUrl().equalsIgnoreCase(url))) {
            errors.put("url", "This career page has already been added.");
            throw new ValidationException(errors);
        }

        JobSource source = writer.createSource(userId, url, label);

        // First extraction attempt. A failure here is recorded on the source rather than
        // thrown, so the source still exists and can be retried or given an adapter later.
        try {
            ingest(source, INTERACTIVE_CHUNKS);
        } catch (Exception ex) {
            log.warn("Initial extraction failed for new source {} ({}): {}",
                    source.getId(), url, ex.getMessage());
            writer.markProgress(source.getId(), null, 0, false,
                    "Initial extraction failed: " + truncate(ex.getMessage(), 300),
                    JobIngestWriter.STATUS_ERROR);
        }

        // Anything beyond the first chunk continues without the user asking again.
        continueInBackground(source.getId());

        // Re-read so the response carries the status the extraction just produced.
        JobSource saved = sources.findById(source.getId()).orElse(source);
        String userName = users.findById(userId).map(User::getName).orElse("Member");
        return toResponse(saved, userName);
    }

    private JobDtos.SourceResponse toResponse(JobSource source, String addedByName) {
        return new JobDtos.SourceResponse(
                source.getId(), source.getUrl(), source.getLabel(),
                source.getLastScrapedAt(), source.getLastError(),
                source.getCreatedAt(), addedByName,
                source.getAdapter(), source.getSyncCursor(), source.getSweepCompletedAt(),
                source.getExtractionStatus(), listings.countBySourceId(source.getId()));
    }

    /**
     * Fetch jobs for one source on behalf of a user request.
     *
     * <p>Deliberately not {@code @Transactional}: this method performs external HTTP calls,
     * and holding a transaction across them would lock the source row for the whole sweep
     * and block operations like deleting it. Persistence happens in short transactions
     * inside {@link JobIngestWriter}.
     *
     * <p>Interactive calls fetch a single chunk so the response comes back quickly; the
     * scheduled job is what works through a large board over successive runs.
     */
    public JobDtos.ScrapeResult scrapeSource(Long userId, Long sourceId) {
        JobSource source = sources.findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));
        // Fetch one chunk now so the caller gets a real count back, then let the rest of the
        // board finish on a background thread instead of requiring repeated clicks.
        JobDtos.ScrapeResult result = ingest(source, INTERACTIVE_CHUNKS);
        continueInBackground(sourceId);
        return result;
    }

    /** Hand the remainder of a board to the background executor, if any is left. */
    private void continueInBackground(Long sourceId) {
        sources.findById(sourceId)
                .filter(source -> source.getSyncCursor() > 0)
                .ifPresent(source -> self.getObject().ingestRemainderInBackground(sourceId));
    }

    /** Entry point for the daily scheduler, which may work through several chunks. */
    public JobDtos.ScrapeResult scrapeSourceInternal(JobSource source) {
        return ingest(source, MAX_CHUNKS_PER_RUN);
    }

    private JobDtos.ScrapeResult ingest(JobSource source, int maxChunks) {
        JobPortalAdapter adapter = findAdapter(source.getUrl());
        return adapter != null
                ? syncViaAdapter(source, adapter, maxChunks)
                : scrapeGenericHtml(source);
    }

    /** The first adapter claiming this URL, or null to fall back to HTML scraping. */
    private JobPortalAdapter findAdapter(String url) {
        return adapters.stream()
                .filter(candidate -> candidate.supports(url))
                .findFirst()
                .orElse(null);
    }

    /**
     * Report whether a career URL can actually be extracted, before the user saves it.
     *
     * <p>This performs a real probe rather than pattern-matching alone: a supported ATS
     * host still fails if the company slug is wrong, and that is exactly the mistake
     * worth catching up front.
     */
    public JobDtos.SupportCheck checkSupport(String rawUrl) {
        Map<String, String> errors = new LinkedHashMap<>();
        String url = validateUrl(rawUrl, errors);
        if (!errors.isEmpty()) {
            return new JobDtos.SupportCheck("NONE", null, null, null, List.of(),
                    errors.values().iterator().next());
        }

        JobPortalAdapter adapter = findAdapter(url);
        JobSource probe = new JobSource();
        probe.setUrl(url);

        if (adapter != null) {
            try {
                JobPortalAdapter.Chunk chunk = adapter.fetchChunk(probe, 0, 5);
                List<String> titles = chunk.jobs().stream()
                        .map(ScrapedJob::title)
                        .filter(java.util.Objects::nonNull)
                        .limit(3)
                        .toList();
                if (chunk.jobs().isEmpty()) {
                    return new JobDtos.SupportCheck("FULL", adapter.name(), companyFrom(url), 0, List.of(),
                            "Recognised as a " + adapter.name() + " board, but it has no open roles right now.");
                }
                return new JobDtos.SupportCheck("FULL", adapter.name(), companyFrom(url),
                        chunk.jobs().size(), titles,
                        "Supported via the " + adapter.name() + " API — full role details will be extracted.");
            } catch (Exception ex) {
                return new JobDtos.SupportCheck("ERROR", adapter.name(), companyFrom(url), null, List.of(),
                        "Looks like a " + adapter.name() + " board, but fetching it failed: "
                                + truncate(ex.getMessage(), 200));
            }
        }

        // No adapter: see whether the raw HTML exposes anything at all.
        try {
            String html = fetchPage(url);
            int found = extractJobLinks(html, url).size();
            if (found == 0) {
                return new JobDtos.SupportCheck("NONE", null, companyFrom(url), 0, List.of(),
                        "This page loads its jobs with JavaScript, so nothing can be extracted from it. "
                                + "A dedicated adapter would be needed for this portal.");
            }
            return new JobDtos.SupportCheck("LIMITED", null, companyFrom(url), found, List.of(),
                    "Partly supported: " + found + " job link(s) found in the page HTML, but role details "
                            + "like level and location may be missing.");
        } catch (Exception ex) {
            return new JobDtos.SupportCheck("ERROR", null, companyFrom(url), null, List.of(),
                    "Could not reach this page: " + truncate(ex.getMessage(), 200));
        }
    }

    /** Best-effort company name for prefilling the label: adapter slug, else hostname. */
    private String companyFrom(String url) {
        JobPortalAdapter adapter = findAdapter(url);
        if (adapter instanceof AbstractJsonJobAdapter jsonAdapter) {
            String slug = jsonAdapter.slugFrom(url);
            if (slug != null) return slug;
        }
        return hostFromUrl(url);
    }

    /**
     * Ingest a portal through its API in resumable chunks.
     *
     * <p>The cursor is persisted after every chunk, so a run cut short by a timeout
     * resumes from the same offset instead of restarting. Dedup is on the portal's own
     * job id, which makes re-fetching an overlapping window harmless — necessary
     * because these result sets are relevance-ordered and shift between requests.
     * A sweep ends only when the portal returns a short chunk; the cursor then resets
     * so the next cycle picks up newly posted jobs.
     */
    private JobDtos.ScrapeResult syncViaAdapter(JobSource source, JobPortalAdapter adapter, int maxChunks) {
        Long sourceId = source.getId();
        Long postedBy = source.getAddedBy();
        String company = source.getLabel() != null ? source.getLabel() : hostFromUrl(source.getUrl());

        int cursor = Math.max(0, source.getSyncCursor());
        int created = 0;
        boolean exhausted = false;

        for (int chunk = 0; chunk < maxChunks; chunk++) {
            JobPortalAdapter.Chunk result;
            try {
                result = adapter.fetchChunk(source, cursor, CHUNK_SIZE);
            } catch (Exception ex) {
                // Leave the cursor where it is so the next run retries this window.
                String errorMsg = "Sync failed at offset " + cursor + ": " + truncate(ex.getMessage(), 300);
                writer.markProgress(sourceId, adapter.name(), cursor, false, errorMsg,
                        JobIngestWriter.STATUS_ERROR);
                log.warn("Adapter {} failed for source {} at offset {}: {}",
                        adapter.name(), sourceId, cursor, ex.getMessage());
                return new JobDtos.ScrapeResult(created, errorMsg);
            }

            created += writer.persistChunk(sourceId, postedBy, company, result.jobs());
            cursor += CHUNK_SIZE;

            if (result.exhausted()) {
                exhausted = true;
                break;
            }
            // Checkpoint between chunks; status is left as-is until the run finishes.
            writer.markProgress(sourceId, adapter.name(), cursor, false, null, null);
        }

        writer.markProgress(sourceId, adapter.name(), cursor, exhausted, null,
                JobIngestWriter.STATUS_FULL);
        log.info("Adapter {} synced source {}: {} new listings, cursor now {}{}",
                adapter.name(), sourceId, created, exhausted ? 0 : cursor,
                exhausted ? " (full sweep complete)" : "");
        return new JobDtos.ScrapeResult(created, null);
    }

    /** Original regex-over-HTML path, used for sources without a dedicated adapter. */
    private JobDtos.ScrapeResult scrapeGenericHtml(JobSource source) {
        String html;
        try {
            html = fetchPage(source.getUrl());
        } catch (Exception ex) {
            String errorMsg = "Fetch failed: " + truncate(ex.getMessage(), 400);
            writer.markProgress(source.getId(), null, source.getSyncCursor(), false, errorMsg,
                    JobIngestWriter.STATUS_ERROR);
            return new JobDtos.ScrapeResult(0, errorMsg);
        }

        String company = source.getLabel() != null ? source.getLabel() : hostFromUrl(source.getUrl());
        List<ScrapedJob> jobs = extractJobLinks(html, source.getUrl()).stream()
                .map(link -> ScrapedJob.basic(link.url(), link.title(), link.description(), null))
                .toList();

        int created = writer.persistChunk(source.getId(), source.getAddedBy(), company, jobs);

        // Nothing in the HTML means this portal renders jobs client-side and needs its
        // own adapter; flag it rather than leaving an empty source that looks broken.
        String status = jobs.isEmpty() ? JobIngestWriter.STATUS_NONE : JobIngestWriter.STATUS_LIMITED;
        String note = jobs.isEmpty()
                ? "No job links found in this page's HTML — it likely loads jobs with JavaScript "
                        + "and needs a dedicated extractor."
                : null;
        writer.markProgress(source.getId(), null, 0, true, note, status);

        log.info("Scraped source {} ({}): found {} links, created {} new listings, status {}",
                source.getId(), source.getUrl(), jobs.size(), created, status);
        return new JobDtos.ScrapeResult(created, note);
    }

    /**
     * Remove a source and detach the listings it produced.
     *
     * <p>Listings are unlinked rather than deleted so that anything a user already applied
     * to stays on the board. Their {@code source_id} is cleared explicitly instead of relying
     * on the foreign key's ON DELETE behaviour, which keeps this correct regardless of how
     * the constraint was created in a given environment.
     *
     * <p>Failures are reported rather than swallowed: silently doing nothing when the caller
     * isn't the owner is indistinguishable from a broken button.
     */
    @Transactional
    public void deleteSource(Long userId, Long sourceId) {
        JobSource source = sources.findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));
        if (!source.getAddedBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the member who added this source can remove it.");
        }

        List<JobListing> attached = listings.findBySourceIdOrderByCreatedAtDesc(sourceId);
        if (!attached.isEmpty()) {
            attached.forEach(listing -> listing.setSourceId(null));
            listings.saveAll(attached);
        }
        sources.delete(source);
        log.info("Deleted source {} and detached {} listing(s)", sourceId, attached.size());
    }

    /**
     * Return all job listings scraped from a given source, with applied status for the user.
     */
    @Transactional(readOnly = true)
    public List<JobDtos.JobResponse> listingsForSource(Long userId, Long sourceId) {
        List<JobListing> jobs = listings.findBySourceIdOrderByCreatedAtDesc(sourceId);
        if (jobs.isEmpty()) return List.of();

        // Load applied status
        List<Long> ids = jobs.stream().map(JobListing::getId).toList();
        Map<Long, java.time.Instant> appliedMap = new java.util.HashMap<>();
        for (var app : applications.findForUserAndListings(userId, ids)) {
            appliedMap.put(app.getId().getListingId(), app.getAppliedAt());
        }

        // Load poster names
        Set<Long> posterIds = jobs.stream().map(JobListing::getPostedBy).collect(Collectors.toSet());
        Map<Long, String> posterNames = users.findAllById(posterIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        return jobs.stream().map(job -> JobDtos.JobResponse.from(job,
                posterNames.getOrDefault(job.getPostedBy(), "Community member"),
                appliedMap.get(job.getId()))).toList();
    }

    // --- Internal helpers ---

    private String fetchPage(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "DSA-Tracker-JobBot/1.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }
        String body = response.body();
        if (body.length() > MAX_BODY_BYTES) {
            body = body.substring(0, MAX_BODY_BYTES);
        }
        return body;
    }

    // How much surrounding HTML (before and after the link) to scan for descriptive
    // text like tech stack keywords ("Java", "Spring", "AWS", etc.) that often sit
    // in a sibling <p>/<span>/<li> near the job link rather than inside the anchor text.
    private static final int DESCRIPTION_CONTEXT_CHARS = 1500;
    private static final int DESCRIPTION_MAX_CHARS = 2000;

    List<ScrapedLink> extractJobLinks(String html, String baseUrl) {
        List<ScrapedLink> results = new ArrayList<>();
        Matcher hrefMatcher = HREF_PATTERN.matcher(html);
        Set<String> seen = new java.util.HashSet<>();

        while (hrefMatcher.find() && results.size() < 50) {
            String href = hrefMatcher.group(1).trim();
            String resolved = resolveUrl(href, baseUrl);
            if (resolved == null) continue;
            if (!JOB_URL_PATTERN.matcher(resolved).find()) continue;
            if (!seen.add(resolved.toLowerCase())) continue;

            // Try to extract title from surrounding anchor text
            String title = null;
            int anchorEnd = html.indexOf("</a>", hrefMatcher.end());
            if (anchorEnd > 0 && anchorEnd - hrefMatcher.end() < 500) {
                String segment = html.substring(hrefMatcher.end(), anchorEnd + 4);
                Matcher titleMatcher = TITLE_PATTERN.matcher(segment);
                if (titleMatcher.find()) {
                    title = titleMatcher.group(1).replaceAll("<[^>]+>", "").trim();
                    if (title.length() < 3 || title.length() > 200) title = null;
                }
            }

            String description = extractDescriptionContext(html, hrefMatcher.start());
            results.add(new ScrapedLink(resolved, title, description));
        }
        return results;
    }

    /**
     * Grab a window of plain text surrounding the link's position in the HTML
     * (e.g. sibling text describing the role/tech stack) so keyword matching
     * against a candidate's resume skills isn't limited to just the anchor title.
     */
    private String extractDescriptionContext(String html, int linkPosition) {
        int start = Math.max(0, linkPosition - DESCRIPTION_CONTEXT_CHARS);
        int end = Math.min(html.length(), linkPosition + DESCRIPTION_CONTEXT_CHARS);
        String window = html.substring(start, end);
        // Strip tags, scripts/styles content, and collapse whitespace
        String text = window
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.isBlank()) return null;
        return text.length() > DESCRIPTION_MAX_CHARS ? text.substring(0, DESCRIPTION_MAX_CHARS) : text;
    }

    private String resolveUrl(String href, String baseUrl) {
        try {
            URI base = new URI(baseUrl);
            URI resolved = base.resolve(href);
            String scheme = resolved.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            if (resolved.getHost() == null || resolved.getHost().isBlank()) return null;
            if (resolved.getRawUserInfo() != null) return null;
            return resolved.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private String hostFromUrl(String url) {
        try { return new URI(url).getHost().replaceFirst("^www\\.", ""); }
        catch (Exception e) { return "External"; }
    }

    private String validateUrl(String value, Map<String, String> errors) {
        if (value == null || value.trim().isEmpty()) {
            errors.put("url", "URL is required.");
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 2048) {
            errors.put("url", "URL must be 2048 characters or fewer.");
            return null;
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                errors.put("url", "Only HTTP and HTTPS URLs are accepted.");
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                errors.put("url", "URL must contain a valid host.");
                return null;
            }
            if (uri.getRawUserInfo() != null) {
                errors.put("url", "URL must not contain credentials.");
                return null;
            }
        } catch (URISyntaxException e) {
            errors.put("url", "Invalid URL format.");
            return null;
        }
        return trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String truncateOrNull(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * Approximate a minimum years-of-experience from a portal's career level label,
     * so experience filtering still works when no explicit number is published.
     */
    static Integer experienceFromCareerLevel(String careerLevel) {
        if (careerLevel == null) return null;
        String level = careerLevel.toLowerCase();
        if (level.contains("intern") || level.contains("student")) return 0;
        if (level.contains("entry") || level.contains("early") || level.contains("graduate")
                || level.contains("associate") || level.contains("junior")) return 0;
        if (level.contains("mid")) return 2;
        if (level.contains("senior")) return 5;
        if (level.contains("manager") || level.contains("lead") || level.contains("principal")) return 8;
        if (level.contains("director") || level.contains("executive")) return 10;
        return null;
    }

    record ScrapedLink(String url, String title, String description) { }

    /**
     * Extract minimum years of experience from a job title/text.
     * Returns null if no explicit experience requirement is mentioned.
     * Looks for patterns like "1+ years", "2-4 years experience", "3 yrs", etc.
     */
    static Integer extractExperience(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase();
        // Pattern: "X+ years" or "X+ yrs" or "X years" or "minimum X years"
        Pattern expPattern = Pattern.compile(
                "(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)\\s*(?:of\\s+)?(?:experience|exp)?|" +
                "(?:minimum|min|at least)\\s*(\\d{1,2})\\s*(?:years?|yrs?)|" +
                "(\\d{1,2})\\s*[-–]\\s*\\d{1,2}\\s*(?:years?|yrs?)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = expPattern.matcher(lower);
        if (m.find()) {
            String num = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            if (num != null) {
                try {
                    int val = Integer.parseInt(num);
                    if (val >= 0 && val <= 30) return val;
                } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }
}
