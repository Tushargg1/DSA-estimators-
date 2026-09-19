package com.dsatracker.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workday-hosted career boards (myworkdayjobs.com).
 *
 * <p>Workday exposes a JSON API at:
 * {@code POST https://{tenant}.{wd_instance}.myworkdayjobs.com/wday/cxs/{tenant}/{site}/jobs}
 * with a JSON body: {@code {"appliedFacets":{},"limit":20,"offset":0,"searchText":""}}
 *
 * <p>Example companies using Workday: NCR Atleos, Samsung, Siemens, PepsiCo, GM, etc.
 */
@Component
public class WorkdayJobAdapter extends AbstractJsonJobAdapter {

    /**
     * Matches Workday URLs like:
     *   https://ncratleos.wd1.myworkdayjobs.com/en-US/ext_apacatleos
     *   https://sec.wd3.myworkdayjobs.com/Samsung_Careers
     *
     * Captures: group(1) = tenant (e.g. "ncratleos")
     *           group(2) = wd instance (e.g. "wd1")
     *           group(3) = site slug (e.g. "ext_apacatleos")
     */
    private static final Pattern WORKDAY_URL = Pattern.compile(
            "([a-z0-9_-]+)\\.(wd\\d+)\\.myworkdayjobs\\.com(?:/[a-z]{2}-[A-Z]{2})?/([a-zA-Z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "workday";
    }

    @Override
    protected Pattern slugPattern() {
        return WORKDAY_URL;
    }

    @Override
    public boolean supports(String url) {
        return url != null && url.contains("myworkdayjobs.com") && WORKDAY_URL.matcher(url).find();
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        Matcher m = WORKDAY_URL.matcher(source.getUrl());
        if (!m.find()) throw new IllegalStateException("Not a Workday URL: " + source.getUrl());

        String tenant = m.group(1);
        String wdInstance = m.group(2);
        String site = m.group(3);

        String apiUrl = String.format(
                "https://%s.%s.myworkdayjobs.com/wday/cxs/%s/%s/jobs",
                tenant, wdInstance, tenant, site);

        String baseUrl = String.format("https://%s.%s.myworkdayjobs.com", tenant, wdInstance);

        Map<String, Object> body = Map.of(
                "appliedFacets", Map.of(),
                "limit", chunkSize,
                "offset", startIndex,
                "searchText", ""
        );

        JsonNode root = postJson(apiUrl, body);

        int total = root.has("total") ? root.get("total").asInt(0) : 0;
        JsonNode postings = root.path("jobPostings");
        if (!postings.isArray()) {
            throw new IllegalStateException("Unexpected Workday response for " + tenant + "/" + site);
        }

        List<ScrapedJob> results = new ArrayList<>();
        for (JsonNode job : postings) {
            String title = text(job, "title");
            String externalPath = text(job, "externalPath");
            if (title == null || externalPath == null) continue;

            String jobUrl = baseUrl + externalPath;
            String location = text(job, "locationsText");
            String postedText = text(job, "postedOn");

            // bulletFields often contains the requisition ID
            String externalId = null;
            JsonNode bullets = job.get("bulletFields");
            if (bullets != null && bullets.isArray() && !bullets.isEmpty()) {
                externalId = bullets.get(0).asText(null);
            }

            results.add(new ScrapedJob(
                    externalId,
                    title,
                    jobUrl,
                    null, // Workday list API does not include descriptions
                    location,
                    null, // employment type not in list API
                    null, // career level not in list API
                    null,
                    postedText,
                    null
            ));
        }

        boolean exhausted = startIndex + results.size() >= total;
        return new Chunk(results, exhausted);
    }
}
