package com.dsatracker.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ashby-hosted boards, covering every company on Ashby.
 *
 * <p>{@code GET api.ashbyhq.com/posting-api/job-board/{slug}} returns the whole board
 * in one response with no paging parameters, so a single fetch satisfies the sweep.
 * Unlike the other ATS APIs it exposes an explicit {@code isListed} flag, which is
 * honoured so unlisted roles are not surfaced.
 */
@Component
public class AshbyJobAdapter extends AbstractJsonJobAdapter {

    private static final Pattern SLUG = Pattern.compile(
            "(?:jobs\\.ashbyhq\\.com|api\\.ashbyhq\\.com/posting-api/job-board)/([a-z0-9_.-]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "ashby";
    }

    @Override
    protected Pattern slugPattern() {
        return SLUG;
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        String slug = slugFrom(source.getUrl());
        if (slug == null) throw new IllegalStateException("Not an Ashby board URL: " + source.getUrl());

        // No offset parameter on this endpoint, so later windows add nothing.
        if (startIndex > 0) return new Chunk(List.of(), true);

        JsonNode root = getJson("https://api.ashbyhq.com/posting-api/job-board/" + slug);
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) throw new IllegalStateException("Unexpected Ashby response for " + slug);

        List<ScrapedJob> results = new ArrayList<>();
        for (JsonNode job : jobs) {
            // Respect the board's own visibility flag when present.
            JsonNode listed = job.get("isListed");
            if (listed != null && listed.isBoolean() && !listed.asBoolean()) continue;

            String title = text(job, "title");
            String url = firstNonBlank(text(job, "jobUrl"), text(job, "applyUrl"));
            if (title == null || url == null) continue;

            String description = firstNonBlank(
                    text(job, "descriptionPlain"),
                    toPlainText(text(job, "descriptionHtml")));
            String extra = firstNonBlank(text(job, "team"), text(job, "department"));
            if (extra != null) description = description == null ? extra : description + " " + extra;

            String location = firstNonBlank(
                    text(job, "location"),
                    joinStringArray(job, "secondaryLocations"));
            JsonNode remote = job.get("isRemote");
            if (remote != null && remote.isBoolean() && remote.asBoolean()) {
                location = location == null ? "Remote" : location + " (Remote)";
            }

            results.add(new ScrapedJob(
                    text(job, "id"),
                    title,
                    url,
                    description,
                    location,
                    // e.g. "FullTime" -> "Full Time"
                    splitCamelCase(text(job, "employmentType")),
                    null, // Ashby has no career-level concept
                    null,
                    relativePosted(text(job, "publishedAt")),
                    null
            ));
        }
        return new Chunk(results, true);
    }

    private static String splitCamelCase(String value) {
        if (value == null) return null;
        return value.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }
}
