package com.dsatracker.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * iCIMS-hosted career boards.
 *
 * <p>iCIMS provides a public JSON search API at:
 * {@code GET https://{tenant}.icims.com/jobs/search?pr=1&format=json&in=1}
 *
 * <p>This returns job listings with ids, titles, and location info. Each job's
 * detail URL is constructed as: {@code https://{tenant}.icims.com/jobs/{jobId}/job}
 *
 * <p>Known companies: Amazon, HP, GE, Pfizer, many defence / government contractors.
 */
@Component
public class IcimsJobAdapter extends AbstractJsonJobAdapter {
    private static final Logger log = LoggerFactory.getLogger(IcimsJobAdapter.class);

    /**
     * Matches iCIMS board URLs like:
     *   https://careers.icims.com/jobs/search (generic portal page)
     *   https://{tenant}.icims.com/jobs/search
     *   https://{tenant}.icims.com/jobs/{id}/job
     *
     * Captures group(1) = tenant subdomain (e.g. "amazon" or "careers")
     */
    private static final Pattern SLUG = Pattern.compile(
            "([a-zA-Z0-9_-]+)\\.icims\\.com(?:/jobs)?",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "icims";
    }

    @Override
    protected Pattern slugPattern() {
        return SLUG;
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        String slug = slugFrom(source.getUrl());
        if (slug == null) throw new IllegalStateException("Not an iCIMS board URL: " + source.getUrl());

        // iCIMS uses page-based pagination (pr = page number, starting at 1).
        // Convert offset-based cursor to page number (their default page size is ~10).
        int pageSize = Math.min(chunkSize, 20);
        int pageNum = (startIndex / pageSize) + 1;

        String apiUrl = "https://" + slug + ".icims.com/jobs/search"
                + "?pr=" + pageNum
                + "&iis=&iisn=&in=1"
                + "&ss=&format=json";

        JsonNode root = getJson(apiUrl);

        // iCIMS returns either a JSON array or an object with "hits"
        JsonNode jobs;
        int total = 0;
        if (root.isArray()) {
            jobs = root;
        } else if (root.has("jobs")) {
            jobs = root.get("jobs");
            total = root.has("count") ? root.get("count").asInt(0) : 0;
        } else if (root.has("results")) {
            jobs = root.get("results");
        } else {
            log.warn("iCIMS unexpected response structure for {}: {}", slug, root.toPrettyString().substring(0, 200));
            return new Chunk(List.of(), true);
        }

        if (!jobs.isArray()) return new Chunk(List.of(), true);

        List<ScrapedJob> results = new ArrayList<>();
        for (JsonNode job : jobs) {
            String title = firstNonBlank(text(job, "jobtitle"), text(job, "title"), text(job, "name"));
            String idStr = firstNonBlank(text(job, "id"), text(job, "jid"));
            if (title == null) continue;

            String jobUrl;
            if (idStr != null) {
                jobUrl = "https://" + slug + ".icims.com/jobs/" + idStr + "/job";
            } else {
                continue; // can't build URL without ID
            }

            String location = firstNonBlank(
                    text(job, "fullLocation"),
                    text(job, "location"),
                    text(job, "city"));

            results.add(new ScrapedJob(
                    idStr,
                    title,
                    jobUrl,
                    toPlainText(text(job, "description")),
                    location,
                    text(job, "jtype"),
                    null,
                    null,
                    null,
                    null
            ));
        }

        boolean exhausted = jobs.size() < pageSize || (total > 0 && startIndex + jobs.size() >= total);
        return new Chunk(results, exhausted);
    }
}
