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
import com.microsoft.playwright.options.WaitUntilState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class CareersAtTechJobAdapter implements JobPortalAdapter {
    private static final Logger log = LoggerFactory.getLogger(CareersAtTechJobAdapter.class);

    @Override
    public String name() {
        return "careersattech";
    }

    @Override
    public boolean supports(String url) {
        return url != null && url.contains("careersat.tech");
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        if (startIndex > 0) return new Chunk(List.of(), true);

        List<ScrapedJob> jobs = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-setuid-sandbox"))
            );

            com.microsoft.playwright.BrowserContext context = browser.newContext();
            Page page = context.newPage();

            page.navigate(source.getUrl(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            try {
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000));
            } catch (Exception e) {
                log.warn("Network idle timeout for {}", source.getUrl());
            }
            
            try {
                String nextDataStr = (String) page.evaluate("() => { const el = document.getElementById('__NEXT_DATA__'); return el ? el.textContent : null; }");
                if (nextDataStr != null) {
                    JsonNode root = mapper.readTree(nextDataStr);
                    extractJobsFromNextData(root, jobs);
                }
            } catch (Exception e) {
                log.error("Failed to parse NEXT_DATA", e);
            }

            browser.close();
        }
        return new Chunk(jobs, true);
    }

    private void extractJobsFromNextData(JsonNode root, List<ScrapedJob> jobs) {
        if (root == null) return;
        try {
            JsonNode initialJobs = root.path("props").path("pageProps").path("initialJobs").path("data");
            if (initialJobs.isArray()) {
                for (JsonNode node : initialJobs) {
                    if (node.has("title") && node.has("slug") && node.has("company")) {
                        String title = node.get("title").asText("");
                        String slug = node.get("slug").asText("");
                        String company = node.path("company").path("slug").asText("");
                        if (!title.isEmpty() && !slug.isEmpty()) {
                            jobs.add(new ScrapedJob(slug, title, "https://careersat.tech/jobs/" + slug, null, null, null, null, null, null, null));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error navigating NEXT_DATA", e);
        }
    }
}
