
package com.dsatracker;
import java.util.*;
import java.util.regex.*;
import com.microsoft.playwright.*;

public class TestPWScrape {
    public static void main(String[] args) {
        String[] urls = {
            "https://globalcareers.lge.com/jobs",
            "https://kpmgindia.talentrecruit.com/career-page",
            "https://airtel.darwinbox.in/ms/candidatev2/main/careers/allJobs"
        };
        
        Pattern JOB_URL_PATTERN = Pattern.compile(
            "(/job|/career|/apply|/position|/opening|/vacanc|/req|/role|/opportunity|/posting|/detail)",
            Pattern.CASE_INSENSITIVE
        );
        Pattern HREF_PATTERN = Pattern.compile(
            "href\\s*=\\s*[\"\\u0027]([^\"\\u0027]{10,2048})[\"\\u0027]",
            Pattern.CASE_INSENSITIVE
        );

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            for (String url : urls) {
                System.out.println("\n=== Scraping " + url + " ===");
                try {
                    BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                    );
                    Page page = context.newPage();
                    page.navigate(url);
                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000));
                    
                    String html = page.content();
                    Matcher m = HREF_PATTERN.matcher(html);
                    int count = 0;
                    while (m.find()) {
                        String link = m.group(1);
                        if (JOB_URL_PATTERN.matcher(link).find()) {
                            System.out.println("  Found job link: " + link);
                            count++;
                            if (count >= 5) break;
                        }
                    }
                    System.out.println("  Total matching links found: " + count);
                    context.close();
                } catch (Exception e) {
                    System.out.println("  Error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

