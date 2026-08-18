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

    private final JobSourceRepository sources;
    private final JobListingRepository listings;
    private final UserRepository users;

    public JobSourceService(JobSourceRepository sources,
                            JobListingRepository listings,
                            UserRepository users) {
        this.sources = sources;
        this.listings = listings;
        this.users = users;
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
                source.getCreatedAt(), names.getOrDefault(source.getAddedBy(), "Member")
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

        List<ScrapedLink> links = extractJobLinks(html, source.getUrl());
        int created = 0;
        for (ScrapedLink link : links) {
            // Avoid duplicates: check if same URL already exists
            boolean exists = listings.findAllByOrderByCreatedAtDescIdDesc(
                    org.springframework.data.domain.PageRequest.of(0, 1000))
                    .getContent().stream()
                    .anyMatch(l -> l.getJobUrl().equalsIgnoreCase(link.url()));
            if (!exists) {
                JobListing listing = new JobListing();
                listing.setTitle(link.title() != null ? link.title() : "Job opportunity");
                listing.setCompany(source.getLabel() != null ? source.getLabel() : hostFromUrl(source.getUrl()));
                listing.setJobUrl(link.url());
                listing.setPostedBy(userId);
                listing.setSourceId(source.getId());
                listing.setCreatedAt(Instant.now());
                listings.save(listing);
                created++;
            }
        }

        source.setLastScrapedAt(Instant.now());
        source.setLastError(null);
        sources.save(source);
        log.info("Scraped source {} ({}): found {} links, created {} new listings",
                sourceId, source.getUrl(), links.size(), created);
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
            results.add(new ScrapedLink(resolved, title));
        }
        return results;
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

    record ScrapedLink(String url, String title) { }
}
