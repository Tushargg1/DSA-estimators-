package com.dsatracker.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Darwinbox-hosted career boards (e.g. airtel.darwinbox.in, zomato.darwinbox.in).
 *
 * <p>Darwinbox is an Angular SPA that renders job listings client-side and calls
 * internal JSON APIs for job data. This adapter intercepts those API calls using
 * Playwright's network interception feature to get structured job data directly.
 *
 * <p>The job detail URL format is:
 * {@code https://{tenant}.darwinbox.in/ms/candidatev2/main/careers/jobDetails/{hash}}
 */
@Component
public class DarwinboxJobAdapter implements JobPortalAdapter {
    private static final Logger log = LoggerFactory.getLogger(DarwinboxJobAdapter.class);

    private static final Pattern DARWINBOX_URL = Pattern.compile(
            "([a-zA-Z0-9_-]+)\\.darwinbox\\.(?:in|com|net|io)/",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "darwinbox";
    }

    @Override
    public boolean supports(String url) {
        return url != null && DARWINBOX_URL.matcher(url).find();
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        if (startIndex > 0) {
            // Darwinbox loads all jobs in one page fetch; subsequent chunks are empty.
            return new Chunk(List.of(), true);
        }

        String url = source.getUrl();
        Matcher m = DARWINBOX_URL.matcher(url);
        if (!m.find()) throw new IllegalStateException("Not a Darwinbox URL: " + url);

        String tenant = m.group(1);
        String baseUrl = "https://" + tenant + ".darwinbox.in";

        // Intercept Darwinbox's internal API calls to get job data as JSON
        List<ScrapedJob> interceptedJobs = new CopyOnWriteArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setArgs(List.of(
                        "--no-sandbox",
                        "--disable-setuid-sandbox",
                        "--disable-dev-shm-usage",
                        "--disable-gpu",
                        "--disable-blink-features=AutomationControlled"
                    ))
            );

            com.microsoft.playwright.BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .setViewportSize(1920, 1080)
                        .setIgnoreHTTPSErrors(true)
            );

            Page page = context.newPage();

            // Intercept API responses containing job data
            page.onResponse(response -> {
                String respUrl = response.url();
                if ((respUrl.contains("/careers/") || respUrl.contains("/candidatev2/")) 
                        && respUrl.contains("darwinbox")) {
                    try {
                        String ct = response.headerValue("content-type");
                        if (ct != null && ct.contains("application/json")) {
                            String body = response.text();
                            if (body.contains("jobDetails") || body.contains("job_title") 
                                    || body.contains("designation") || body.contains("position")) {
                                JsonNode root = mapper.readTree(body);
                                extractJobsFromJson(root, baseUrl, tenant, interceptedJobs);
                                log.debug("Darwinbox intercepted {} jobs from {}", interceptedJobs.size(), respUrl);
                            }
                        }
                    } catch (Exception ignored) { }
                }
            });

            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30000));

            // Wait for network to settle and Angular to render
            try {
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(15000));
            } catch (Exception e) {
                log.debug("NETWORKIDLE timeout for Darwinbox {} — using whatever loaded", tenant);
            }

            // Scroll to trigger lazy loading
            try {
                page.evaluate("() => { window.scrollTo(0, document.body.scrollHeight); }");
                page.waitForTimeout(2000);
            } catch (Exception ignored) { }

            // If network interception found jobs, use those
            if (!interceptedJobs.isEmpty()) {
                log.info("Darwinbox {}: found {} jobs via API interception", tenant, interceptedJobs.size());
                browser.close();
                return new Chunk(interceptedJobs, true);
            }

            // Fallback: Extract via DOM
            List<ScrapedJob> domJobs = extractFromDom(page, baseUrl, tenant);
            browser.close();

            if (domJobs.isEmpty()) {
                log.warn("Darwinbox {}: no jobs found via API interception or DOM extraction", tenant);
            } else {
                log.info("Darwinbox {}: found {} jobs via DOM extraction", tenant, domJobs.size());
            }

            return new Chunk(domJobs, true);
        }
    }

    private void extractJobsFromJson(JsonNode node, String baseUrl, String tenant,
                                      List<ScrapedJob> jobs) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode item : node) {
                extractJobsFromJson(item, baseUrl, tenant, jobs);
            }
            return;
        }
        if (!node.isObject()) return;

        // Look for job-like objects: must have a title/designation field and an id
        String title = firstNonNull(
                textOf(node, "job_title"), textOf(node, "designation"),
                textOf(node, "title"), textOf(node, "position_name"),
                textOf(node, "name"));
        String id = firstNonNull(
                textOf(node, "hash"), textOf(node, "job_id"),
                textOf(node, "id"), textOf(node, "job_hash"));

        if (title != null && title.length() > 2 && id != null) {
            String jobUrl = baseUrl + "/ms/candidatev2/main/careers/jobDetails/" + id;
            String location = firstNonNull(
                    textOf(node, "location"), textOf(node, "city"),
                    textOf(node, "work_location"));
            String dept = firstNonNull(
                    textOf(node, "department"), textOf(node, "function"),
                    textOf(node, "team"));

            // Avoid duplicates
            boolean alreadyPresent = jobs.stream().anyMatch(j -> id.equals(j.externalId()));
            if (!alreadyPresent) {
                jobs.add(new ScrapedJob(id, title, jobUrl, dept, location, null, null, null, null, null));
            }
        }

        // Recurse into child arrays and objects
        node.fields().forEachRemaining(entry -> {
            JsonNode child = entry.getValue();
            if (child.isArray() || child.isObject()) {
                extractJobsFromJson(child, baseUrl, tenant, jobs);
            }
        });
    }

    private List<ScrapedJob> extractFromDom(Page page, String baseUrl, String tenant) {
        List<ScrapedJob> results = new ArrayList<>();
        try {
            Object extracted = page.evaluate(
                "() => {" +
                "  let jobs = []; let seen = new Set();" +
                "  let cards = document.querySelectorAll('ui-job-tile, [class*=job-tile], [class*=job-card],[class*=jobCard],[class*=job-item],[class*=jobOpening],[class*=job-opening],[data-job-id],[data-id]');" +
                "  for(let card of cards) {" +
                "    let jobId = card.getAttribute('data-job-id') || card.getAttribute('data-id') || card.getAttribute('data-hash');" +
                "    if (!jobId) {" +
                "       let wrapper = card.closest('.jobs-section');" +
                "       if (wrapper && wrapper.id) jobId = wrapper.id;" +
                "       else {" +
                "         let t = card.querySelector('[data-testid]');" +
                "         if (t && t.getAttribute('data-testid').includes('job-tile-')) jobId = t.getAttribute('data-testid').split('-').find(s => s.length > 10 && !s.includes('tile'));" +
                "       }" +
                "    }" +
                "    let titleEl = card.querySelector('h1,h2,h3,h4,h5,[class*=title],[class*=designation],[class*=position-name],[class*=role]');" +
                "    let title = titleEl ? titleEl.innerText.trim() : '';" +
                "    if(title.length > 120) title = title.substring(0, 120).trim();" +
                "    if(title.length < 2) continue;" +
                "    let anchor = card.querySelector('a[href]');" +
                "    let cardUrl = anchor ? anchor.href : null;" +
                "    if(!cardUrl && jobId) {" +
                "      let base = window.location.origin + window.location.pathname.replace(/\\/allJobs.*/, '');" +
                "      cardUrl = base + '/jobDetails/' + jobId;" +
                "    }" +
                "    if(cardUrl && !seen.has(cardUrl)) {" +
                "      seen.add(cardUrl);" +
                "      let loc = card.querySelector('[class*=location],[class*=city]');" +
                "      jobs.push({url:cardUrl, title:title, location:loc?loc.innerText.trim():null});" +
                "    }" +
                "  }" +
                "  return jobs;" +
                "}"
            );

            if (extracted instanceof java.util.List) {
                for (Object o : (java.util.List<?>) extracted) {
                    if (o instanceof java.util.Map) {
                        java.util.Map<?, ?> map = (java.util.Map<?, ?>) o;
                        String jobUrl = (String) map.get("url");
                        String title = (String) map.get("title");
                        String location = (String) map.get("location");
                        if (jobUrl != null && title != null) {
                            // Include location as description context for keyword matching
                            results.add(new ScrapedJob(null, title, jobUrl, location, location, null, null, null, null, null));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Darwinbox DOM extraction failed for {}: {}", tenant, e.getMessage());
        }
        return results;
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null) return v;
        return null;
    }
}
