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
public class JioJobAdapter implements JobPortalAdapter {
    private static final Logger log = LoggerFactory.getLogger(JioJobAdapter.class);

    @Override
    public String name() {
        return "jio";
    }

    @Override
    public boolean supports(String url) {
        return url != null && url.contains("careers.jio.com");
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        if (startIndex > 0) return new Chunk(List.of(), true); // pagination could be supported but fetch all at once for now

        List<ScrapedJob> jobs = new ArrayList<>();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-setuid-sandbox")));

            com.microsoft.playwright.BrowserContext context = browser.newContext();
            Page page = context.newPage();

            page.navigate(source.getUrl(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            Object extracted = page.evaluate(
                "() => {" +
                "  let jobs = [];" +
                "  let links = document.querySelectorAll('a[id*=lnkbtnJobTitle]');" +
                "  for (let link of links) {" +
                "    let title = link.innerText ? link.innerText.trim() : '';" +
                "    if (title.length > 2) {" +
                "       jobs.push({title: title, url: window.location.href});" +
                "    }" +
                "  }" +
                "  return jobs;" +
                "}"
            );

            if (extracted instanceof java.util.List) {
                for (Object o : (java.util.List<?>) extracted) {
                    if (o instanceof java.util.Map) {
                        java.util.Map<?, ?> map = (java.util.Map<?, ?>) o;
                        String title = (String) map.get("title");
                        String jobUrl = (String) map.get("url");
                        if (title != null && !title.isEmpty()) {
                            jobs.add(new ScrapedJob(null, title, jobUrl, null, null, null, null, null, null, null));
                        }
                    }
                }
            }
            browser.close();
        }
        return new Chunk(jobs, true);
    }
}
