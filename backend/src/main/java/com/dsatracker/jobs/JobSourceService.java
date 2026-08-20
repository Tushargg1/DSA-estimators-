package com.dsatracker.jobs;

import com.dsatracker.model.User;
import com.dsatracker.repository.UserRepository;
import com.dsatracker.web.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

    private final JobSourceRepository sources;
    private final JobListingRepository listings;
    private final JobApplicationRepository applications;
    private final UserRepository users;
    private final List<JobPortalAdapter> adapters;

    public JobSourceService(JobSourceRepository sources,
                            JobListingRepository listings,
                            JobApplicationRepository applications,
                            UserRepository users,
                            List<JobPortalAdapter> adapters) {
        this.sources = sources;
        this.listings = listings;
        this.applications = applications;
        this.users = users;
        this.adapters = adapters;
    }

    @Transactional(readOnly = true)
    public List<JobDtos.SourceResponse> listSources() {
        List<JobSource> allSources = sources.findAllByOrderByCreatedAtDesc();
        if (allSources.isEmpty()) return List.of();
        Collection<Long> userIds = allSources.stream().map(JobSource::getAddedBy).collect(Collectors.toSet());
        Map<Long, String> names = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        return allSources.stream().map(source -> new JobDtos.SourceResponse(
                source.getId(), source.getUrl(), source.getLabel(),
                source.getLastScrapedAt(), source.getLastError(),
                source.getCreatedAt(), names.getOrDefault(source.getAddedBy(), "Member"),
                source.getAdapter(), source.getSyncCursor(), source.getSweepCompletedAt()
        )).toList();
    }

    @Transactional
    public JobDtos.SourceResponse addSource(Long userId, JobDtos.CreateSourceRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        String url = validateUrl(request == null ? null : request.url(), errors);
        String label = request == null ? null : request.label();
        if (label != null) label = label.trim().isEmpty() ? null : label.trim();
        if (label != null && label.length() > 200) label = label.substring(0, 200);
        if (!errors.isEmpty()) throw new ValidationException(errors);

        if (sources.findAllByOrderByCreatedAtDesc().size() >= MAX_SOURCES) {
            errors.put("url", "Maximum of " + MAX_SOURCES + " sources reached.");
            throw new ValidationException(errors);
        }

        JobSource source = new JobSource();
        source.setAddedBy(userId);
        source.setUrl(url);
        source.setLabel(label);
        source.setCreatedAt(Instant.now());
        sources.save(source);

        String userName = users.findById(userId).map(User::getName).orElse("Member");
        return new JobDtos.SourceResponse(source.getId(), source.getUrl(), source.getLabel(),
                null, null, source.getCreatedAt(), userName);
    }

    /**
     * Scrape a source URL and create listings for any new job links found.
     * Returns the count of new listings created plus any error message.
     */
    @Transactional
    public JobDtos.ScrapeResult scrapeSource(Long userId, Long sourceId) {
        JobSource source = sources.findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));
        return scrapeSourceInternal(source);
    }

    /**
     * Internal scrape method usable by the scheduler (no userId needed for lookup).
     * Creates listings with postedBy = source.addedBy.
     */
    @Transactional
    public JobDtos.ScrapeResult scrapeSourceInternal(JobSource source) {
        JobPortalAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.supports(source.getUrl()))
                .findFirst()
                .orElse(null);

        return adapter != null
                ? syncViaAdapter(source, adapter)
                : scrapeGenericHtml(source);
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
    private JobDtos.ScrapeResult syncViaAdapter(JobSource source, JobPortalAdapter adapter) {
        source.setAdapter(adapter.name());
        String company = source.getLabel() != null ? source.getLabel() : hostFromUrl(source.getUrl());

        int cursor = Math.max(0, source.getSyncCursor());
        int created = 0;
        boolean exhausted = false;

        for (int chunk = 0; chunk < MAX_CHUNKS_PER_RUN; chunk++) {
            JobPortalAdapter.Chunk result;
            try {
                result = adapter.fetchChunk(source, cursor, CHUNK_SIZE);
            } catch (Exception ex) {
                // Preserve the cursor so the next run retries this same window.
                String errorMsg = "Sync failed at offset " + cursor + ": " + truncate(ex.getMessage(), 300);
                source.setLastError(errorMsg);
                source.setLastScrapedAt(Instant.now());
                source.setSyncCursor(cursor);
                sources.save(source);
                log.warn("Adapter {} failed for source {} at offset {}: {}",
                        adapter.name(), source.getId(), cursor, ex.getMessage());
                return new JobDtos.ScrapeResult(created, errorMsg);
            }

            created += persistNewJobs(source, company, result.jobs());
            cursor += CHUNK_SIZE;

            if (result.exhausted()) {
                exhausted = true;
                break;
            }

            // Checkpoint mid-sweep so progress survives an interrupted run.
            source.setSyncCursor(cursor);
            sources.save(source);
        }

        if (exhausted) {
            source.setSyncCursor(0);
            source.setSweepCompletedAt(Instant.now());
        } else {
            source.setSyncCursor(cursor);
        }
        source.setLastScrapedAt(Instant.now());
        source.setLastError(null);
        sources.save(source);

        log.info("Adapter {} synced source {}: {} new listings, cursor now {}{}",
                adapter.name(), source.getId(), created, source.getSyncCursor(),
                exhausted ? " (full sweep complete)" : "");
        return new JobDtos.ScrapeResult(created, null);
    }

    /**
     * Insert postings not already stored for this source.
     *
     * <p>Adapter results dedup on the portal job id; generic scrape results have no
     * such id and fall back to matching on URL.
     */
    private int persistNewJobs(JobSource source, String company, List<ScrapedJob> jobs) {
        if (jobs.isEmpty()) return 0;

        Set<String> externalIds = jobs.stream()
                .map(ScrapedJob::externalId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        // Mutable: also tracks ids inserted earlier in this same chunk.
        Set<String> known = externalIds.isEmpty()
                ? new java.util.HashSet<>()
                : new java.util.HashSet<>(listings.findExistingExternalIds(source.getId(), externalIds));

        int created = 0;
        for (ScrapedJob job : jobs) {
            if (job.externalId() != null) {
                if (known.contains(job.externalId())) continue;
            } else if (listings.existsByJobUrlIgnoreCase(job.url())) {
                continue;
            }

            JobListing listing = new JobListing();
            listing.setTitle(job.title() != null ? job.title() : "Job opportunity");
            listing.setCompany(company);
            listing.setJobUrl(job.url());
            listing.setDescription(job.description());
            listing.setExternalId(job.externalId());
            listing.setLocation(truncateOrNull(job.location(), 300));
            listing.setEmploymentType(truncateOrNull(job.employmentType(), 100));
            listing.setCareerLevel(truncateOrNull(job.careerLevel(), 100));
            listing.setQualification(truncateOrNull(job.qualification(), 500));
            listing.setPostedText(truncateOrNull(job.postedText(), 120));
            listing.setPostedBy(source.getAddedBy());
            listing.setSourceId(source.getId());

            // Prefer the portal's stated experience, else infer it from the text.
            Integer experience = job.yearsExperience();
            if (experience == null) experience = extractExperience(job.title());
            if (experience == null) experience = extractExperience(job.description());
            if (experience == null) experience = experienceFromCareerLevel(job.careerLevel());
            listing.setExperienceRequired(experience);

            listing.setCreatedAt(Instant.now());
            listings.save(listing);
            created++;
            // Guard against duplicates inside a single chunk.
            if (job.externalId() != null) known.add(job.externalId());
        }
        return created;
    }

    /** Original regex-over-HTML path, used for sources without a dedicated adapter. */
    private JobDtos.ScrapeResult scrapeGenericHtml(JobSource source) {
        String html;
        try {
            html = fetchPage(source.getUrl());
        } catch (Exception ex) {
            String errorMsg = "Fetch failed: " + truncate(ex.getMessage(), 400);
            source.setLastError(errorMsg);
            source.setLastScrapedAt(Instant.now());
            sources.save(source);
            return new JobDtos.ScrapeResult(0, errorMsg);
        }

        String company = source.getLabel() != null ? source.getLabel() : hostFromUrl(source.getUrl());
        List<ScrapedJob> jobs = extractJobLinks(html, source.getUrl()).stream()
                .map(link -> ScrapedJob.basic(link.url(), link.title(), link.description(), null))
                .toList();

        int created = persistNewJobs(source, company, jobs);

        source.setLastScrapedAt(Instant.now());
        source.setLastError(null);
        sources.save(source);
        log.info("Scraped source {} ({}): found {} links, created {} new listings",
                source.getId(), source.getUrl(), jobs.size(), created);
        return new JobDtos.ScrapeResult(created, null);
    }

    @Transactional
    public void deleteSource(Long userId, Long sourceId) {
        sources.findById(sourceId).ifPresent(source -> {
            if (source.getAddedBy().equals(userId)) {
                sources.delete(source);
            }
        });
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
