package com.dsatracker.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Greenhouse-hosted boards, covering every company on Greenhouse.
 *
 * <p>{@code GET boards-api.greenhouse.io/v1/boards/{slug}/jobs?content=true}
 * returns the whole board in one response with no paging parameters, so a single
 * fetch satisfies the sweep. Descriptions arrive as entity-escaped HTML.
 */
@Component
public class GreenhouseJobAdapter extends AbstractJsonJobAdapter {

    /** Matches both the public board URL and the API host. */
    private static final Pattern SLUG = Pattern.compile(
            "(?:boards|job-boards)(?:-api)?\\.greenhouse\\.io/(?:embed/job_board\\?for=)?([a-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "greenhouse";
    }

    @Override
    protected Pattern slugPattern() {
        return SLUG;
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        String slug = slugFrom(source.getUrl());
        if (slug == null) throw new IllegalStateException("Not a Greenhouse board URL: " + source.getUrl());

        // The API has no offset parameter, so anything past the first window means
        // the board was already ingested in full by an earlier chunk.
        if (startIndex > 0) return new Chunk(List.of(), true);

        JsonNode root = getJson("https://boards-api.greenhouse.io/v1/boards/"
                + slug + "/jobs?content=true");
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) throw new IllegalStateException("Unexpected Greenhouse response for " + slug);

        List<ScrapedJob> results = new ArrayList<>();
        for (JsonNode job : jobs) {
            String title = text(job, "title");
            String url = text(job, "absolute_url");
            if (title == null || url == null) continue;

            // Fold department/office into the text so skill matching can see them.
            String description = toPlainText(text(job, "content"));
            String extra = firstNonBlank(
                    joinObjectArray(job, "departments", "name"),
                    joinObjectArray(job, "offices", "name"));
            if (extra != null) description = description == null ? extra : description + " " + extra;

            results.add(new ScrapedJob(
                    firstNonBlank(text(job, "id"), text(job, "requisition_id")),
                    title,
                    url,
                    description,
                    nested(job, "location", "name"),
                    null, // Greenhouse does not publish employment type on this endpoint
                    null, // nor a career level
                    null,
                    relativePosted(firstNonBlank(text(job, "first_published"), text(job, "updated_at"))),
                    null
            ));
        }
        return new Chunk(results, true);
    }
}
