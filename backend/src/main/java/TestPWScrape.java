import java.util.*;
import java.util.regex.*;
import com.microsoft.playwright.*;

public class TestPWScrape {
    public static void main(String[] args) {
        String[] urls = {
            "https://globalcareers.lge.com/jobs",
            "https://kpmgindia.talentrecruit.com/career-page",
            "https://airtel.darwinbox.in/ms/candidatev2/main/careers/allJobs",
            "https://careersat.tech/jobs",
            "https://careers.jio.com/frmfuncwisejob.aspx?func=098qkj0vwzk%3d&desc=tBOU2f2ubJIKJIaEorlljoC0j8hJb9P7"
        };
        
        Pattern JOB_URL_PATTERN = Pattern.compile(
            "(/job|/career|/apply|/position|/opening|/vacanc|/req|/role|/opportunity|/posting|/detail|/join|/intern|/talent|/hiring|/work-with|/open-position|/current-open|join-us|work-with-us|frmfuncwisejob)",
            Pattern.CASE_INSENSITIVE
        );
        Pattern ANY_URL_PATTERN = Pattern.compile(
            "[\"\\u0027]((?:https?://[^\"\\u0027]+|/[^\"\\u0027]+))[\"\\u0027]",
            Pattern.CASE_INSENSITIVE
        );

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of("--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--disable-blink-features=AutomationControlled"))
            );
            for (String url : urls) {
                System.out.println("\n=== Scraping " + url + " ===");
                try {
                    BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    );
                    Page page = context.newPage();
                    page.navigate(url);
                    try {
                        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(15000));
                    } catch(Exception e){}
                    
                    String jsExtractor = 
                        "() => { " +
                        "  let jobs = []; " +
                        "  let elements = document.querySelectorAll('a, [class*=job], [class*=position], [class*=vacancy], [class*=card], li, div'); " +
                        "  for(let el of elements) { " +
                        "    let title = el.innerText ? el.innerText.trim() : ''; " +
                        "    if(title.length > 5 && title.length < 150 && !title.includes('\\n') && " +
                        "       (title.toLowerCase().includes('engineer') || title.toLowerCase().includes('manager') || title.toLowerCase().includes('developer') || title.toLowerCase().includes('analyst') || title.toLowerCase().includes('consultant'))) { " +
                        "       let url = el.href || (window.location.href.split('#')[0] + '#/job-' + encodeURIComponent(title.replace(/\\s+/g, '-').toLowerCase())); " +
                        "       if(url.startsWith('http')) jobs.push({url: url, title: title}); " +
                        "    } " +
                        "  } " +
                        "  return jobs; " +
                        "}";

                    String html = page.content();
                    
                    try {
                        Object extracted = page.evaluate(jsExtractor);
                        if (extracted instanceof List) {
                            for (Object o : (List<?>) extracted) {
                                if (o instanceof Map) {
                                    Map<?, ?> map = (Map<?, ?>) o;
                                    String jUrl = (String) map.get("url");
                                    String jTitle = (String) map.get("title");
                                    if (jUrl != null) {
                                        html += "\\n<a href=\"" + jUrl + "\">" + (jTitle != null ? jTitle : "Job") + "</a>";
                                    }
                                }
                            }
                        }
                    } catch(Exception e){}
                    
                    Matcher m = ANY_URL_PATTERN.matcher(html);
                    int count = 0;
                    Set<String> seen = new HashSet<>();
                    while (m.find()) {
                        String link = m.group(1).trim();
                        String lower = link.toLowerCase();
                        if (lower.matches(".*\\.(png|jpg|jpeg|gif|svg|ico|css|js|woff|woff2|ttf|eot)(\\?.*)?$")) continue;
                        if (JOB_URL_PATTERN.matcher(link).find() && seen.add(lower)) {
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

