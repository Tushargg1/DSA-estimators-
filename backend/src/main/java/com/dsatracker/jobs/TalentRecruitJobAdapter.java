package com.dsatracker.jobs;

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

@Component
public class TalentRecruitJobAdapter implements JobPortalAdapter {
    private static final Logger log = LoggerFactory.getLogger(TalentRecruitJobAdapter.class);

    @Override
    public String name() {
        return "talentrecruit";
    }

    @Override
    public boolean supports(String url) {
        return url != null && url.contains("talentrecruit.com");
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        if (startIndex > 0) return new Chunk(List.of(), true); // fetch all in one go

        List<ScrapedJob> jobs = new ArrayList<>();
        try (Playwright playwright = Playwright.create()) {
            // Must run headed to bypass Cloudflare/WAF on TalentRecruit
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setArgs(List.of(
                            "--no-sandbox",
                            "--disable-setuid-sandbox",
                            "--disable-blink-features=AutomationControlled"
                    ))
            );

            com.microsoft.playwright.BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .setViewportSize(1920, 1080)
            );

            Page page = context.newPage();
            page.navigate(source.getUrl(), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60000));

            // Wait for Angular to render the jobs
            try {
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(20000));
            } catch (Exception e) {}
            page.waitForTimeout(5000); // Give it extra time for API calls and decryption

            boolean hasMore = true;
            int pageCount = 0;
            while (hasMore && pageCount < 20) { // Safety limit of 20 pages
                pageCount++;
                page.waitForTimeout(3000); // Wait for API and rendering
                
                hasMore = false;
                for (com.microsoft.playwright.Frame frame : page.frames()) {
                    Object extracted = frame.evaluate(
                        "() => {" +
                        "  let res = []; let seen = new Set();" +
                        "  let cards = document.querySelectorAll('.card-wrap, .job-card, .card');" +
                        "  for (let card of cards) {" +
                        "    let titleEl = card.querySelector('.job-title, h1, h2, h3, h4, h5, a');" +
                        "    if (!titleEl) continue;" +
                        "    let title = titleEl.innerText ? titleEl.innerText.trim() : '';" +
                        "    if (title.length < 5) continue;" +
                        "    let url = window.location.href;" + 
                        "    let anchor = card.querySelector('a');" +
                        "    if (anchor && anchor.href) url = anchor.href;" +
                        "    let id = title + url;" +
                        "    if (!seen.has(id)) {" +
                        "      seen.add(id);" +
                        "      res.push({title: title, url: url});" +
                        "    }" +
                        "  }" +
                        "  let nextBtn = document.querySelector('.mat-paginator-navigation-next');" +
                        "  let canClick = false;" +
                        "  if (nextBtn && !nextBtn.disabled && !nextBtn.hasAttribute('disabled')) {" +
                        "     nextBtn.click();" +
                        "     canClick = true;" +
                        "  }" +
                        "  return { jobs: res, hasNext: canClick };" +
                        "}"
                    );

                    if (extracted instanceof java.util.Map) {
                        java.util.Map<?, ?> resultMap = (java.util.Map<?, ?>) extracted;
                        Object jobsList = resultMap.get("jobs");
                        if (jobsList instanceof java.util.List) {
                            for (Object o : (java.util.List<?>) jobsList) {
                                if (o instanceof java.util.Map) {
                                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) o;
                                    String title = (String) map.get("title");
                                    String jobUrl = (String) map.get("url");
                                    if (title != null && !title.isEmpty()) {
                                        // avoid duplicates in the main list
                                        boolean exists = jobs.stream().anyMatch(j -> j.title().equals(title));
                                        if (!exists) {
                                            jobs.add(new ScrapedJob(null, title, jobUrl, null, null, null, null, null, null, null));
                                        }
                                    }
                                }
                            }
                        }
                        if (Boolean.TRUE.equals(resultMap.get("hasNext"))) {
                            hasMore = true;
                        }
                    }
                }
            }


            browser.close();
        }
        return new Chunk(jobs, true);
    }
}
