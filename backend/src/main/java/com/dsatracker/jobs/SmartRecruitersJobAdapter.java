package com.dsatracker.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SmartRecruiters-hosted boards, covering every company on SmartRecruiters.
 *
 * <p>{@code GET api.smartrecruiters.com/v1/companies/{slug}/postings?limit=100&offset=N}
 * returns paginated job postings with full metadata including location, employment type,
 * and experience level. The job page URL is constructed as:
 * {@code https://careers.smartrecruiters.com/{slug}/{uuid}}
 *
 * <p>Known companies: Bosch Group, KPMG, McDonald's, Lidl, and many others.
 */
@Component
public class SmartRecruitersJobAdapter extends AbstractJsonJobAdapter {

    /**
     * Matches SmartRecruiters board URLs like:
     *   https://careers.smartrecruiters.com/BoschGroup
     *   https://www.smartrecruiters.com/jobs/{Slug}
     *   https://jobs.smartrecruiters.com/{Slug}
     *
     * Captures: group(1) = company slug (e.g. "BoschGroup")
     */
    private static final Pattern SLUG = Pattern.compile(
            "(?:careers|jobs|www)\\.smartrecruiters\\.com/([a-zA-Z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "smartrecruiters";
    }

    @Override
    protected Pattern slugPattern() {
        return SLUG;
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        String slug = slugFrom(source.getUrl());
        if (slug == null) throw new IllegalStateException("Not a SmartRecruiters board URL: " + source.getUrl());

        // SmartRecruiters supports offset + limit pagination.
        // Cap at 100 per request (their documented max).
        int safeLimit = Math.min(chunkSize, 100);

        String apiUrl = "https://api.smartrecruiters.com/v1/companies/" + slug
                + "/postings?limit=" + safeLimit + "&offset=" + startIndex;

        JsonNode root = getJson(apiUrl);

        int totalFound = root.has("totalFound") ? root.get("totalFound").asInt(0) : 0;
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            throw new IllegalStateException("Unexpected SmartRecruiters response for " + slug);
        }

        List<ScrapedJob> results = new ArrayList<>();
        for (JsonNode job : content) {
            String title = text(job, "name");
            String uuid = text(job, "uuid");
            String id = text(job, "id");
            if (title == null || uuid == null) continue;

            // Construct the public job listing URL
            String jobUrl = "https://careers.smartrecruiters.com/" + slug + "/" + uuid;

            // Location
            JsonNode location = job.get("location");
            String locationStr = null;
            if (location != null && !location.isNull()) {
                String fullLoc = text(location, "fullLocation");
                if (fullLoc != null) {
                    locationStr = fullLoc;
                } else {
                    String city = text(location, "city");
                    String country = text(location, "country");
                    locationStr = city != null && country != null ? city + ", " + country
                            : city != null ? city : country;
                }
                // Append remote/hybrid indicator
                JsonNode remote = location.get("remote");
                JsonNode hybrid = location.get("hybrid");
                if (remote != null && remote.asBoolean()) {
                    locationStr = locationStr != null ? locationStr + " (Remote)" : "Remote";
                } else if (hybrid != null && hybrid.asBoolean()) {
                    locationStr = locationStr != null ? locationStr + " (Hybrid)" : "Hybrid";
                }
            }

            // Employment type
            String employmentType = nested(job, "typeOfEmployment", "label");

            // Experience level / career level
            String careerLevel = nested(job, "experienceLevel", "label");

            // Department / function for description context
            String dept = nested(job, "department", "label");
            String func = nested(job, "function", "label");
            String description = firstNonBlank(dept, func);

            results.add(new ScrapedJob(
                    firstNonBlank(id, uuid),
                    title,
                    jobUrl,
                    description,
                    locationStr,
                    employmentType,
                    careerLevel,
                    null,
                    relativePosted(text(job, "releasedDate")),
                    null
            ));
        }

        boolean exhausted = startIndex + content.size() >= totalFound || content.size() < safeLimit;
        return new Chunk(results, exhausted);
    }
}
