package com.dsatracker.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lever-hosted boards, covering every company on Lever.
 *
 * <p>{@code GET api.lever.co/v0/postings/{slug}?mode=json} returns a bare JSON array
 * and honours {@code skip}/{@code limit}, so this adapter pages natively.
 *
 * <p>Note the two distinct "empty" responses: an unknown slug yields
 * {@code {"ok":false,"error":"Document not found"}} while a real board with nothing
 * open yields {@code []}. Only the former is an error.
 */
@Component
public class LeverJobAdapter extends AbstractJsonJobAdapter {

    private static final Pattern SLUG = Pattern.compile(
            "(?:jobs|api)\\.lever\\.co/(?:v0/postings/)?([a-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "lever";
    }

    @Override
    protected Pattern slugPattern() {
        return SLUG;
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        String slug = slugFrom(source.getUrl());
        if (slug == null) throw new IllegalStateException("Not a Lever board URL: " + source.getUrl());

        JsonNode root = getJson("https://api.lever.co/v0/postings/" + slug
                + "?mode=json&skip=" + startIndex + "&limit=" + chunkSize);

        // A JSON object here means the error envelope rather than a postings array.
        if (root.isObject() && root.has("ok") && !root.path("ok").asBoolean(true)) {
            throw new IllegalStateException("Lever board \"" + slug + "\" not found: "
                    + root.path("error").asText("unknown error"));
        }
        if (!root.isArray()) throw new IllegalStateException("Unexpected Lever response for " + slug);

        List<ScrapedJob> results = new ArrayList<>();
        for (JsonNode job : root) {
            String title = text(job, "text");
            String url = firstNonBlank(text(job, "hostedUrl"), text(job, "applyUrl"));
            if (title == null || url == null) continue;

            JsonNode categories = job.get("categories");
            String description = firstNonBlank(
                    text(job, "descriptionPlain"),
                    toPlainText(text(job, "description")));

            // Team/department carry the tech context that matters for matching.
            String extra = firstNonBlank(
                    text(categories, "team"),
                    text(categories, "department"));
            if (extra != null) description = description == null ? extra : description + " " + extra;

            String location = firstNonBlank(
                    text(categories, "location"),
                    joinStringArray(categories, "allLocations"),
                    text(job, "country"));
            String workplace = text(job, "workplaceType");
            if (workplace != null && location != null) location = location + " (" + workplace + ")";

            results.add(new ScrapedJob(
                    text(job, "id"),
                    title,
                    url,
                    description,
                    location,
                    text(categories, "commitment"),
                    null, // Lever has no career-level concept
                    null,
                    relativePosted(text(job, "createdAt")),
                    null
            ));
        }
        // Lever pages properly, so a short page means the board is exhausted.
        return new Chunk(results, root.size() < chunkSize);
    }
}
