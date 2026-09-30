import java.util.regex.*;
import java.util.*;

public class TestAll {
    public static void main(String[] args) throws Exception {
        String[] urls = {
            "https://globalcareers.lge.com/jobs",
            "https://kpmgindia.talentrecruit.com/career-page",
            "https://careersat.tech/jobs",
            "https://careers.jio.com/frmfuncwisejob.aspx?func=098qkj0vwzk%3d&desc=tBOU2f2ubJIKJIaEorlljoC0j8hJb9P7",
            "https://airtel.darwinbox.in/ms/candidatev2/main/careers/allJobs"
        };
        
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS).build();

        Pattern ANY_URL_PATTERN = Pattern.compile("[\"\\u0027]((?:https?://[^\"\\u0027]+|/[^\"\\u0027]+))[\"\\u0027]", Pattern.CASE_INSENSITIVE);
        Pattern JOB_URL_PATTERN = Pattern.compile("(/job|/career|/apply|/position|/opening|/vacanc|/req|/role|/opportunity|/posting|/detail|/join|/intern|/talent|/hiring|/work-with|/open-position|/current-open|join-us|work-with-us|frmfuncwisejob)", Pattern.CASE_INSENSITIVE);
        
        for (String url : urls) {
            System.out.println("Testing " + url);
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .build();
            try {
                String html = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
                Matcher m = ANY_URL_PATTERN.matcher(html);
                Set<String> seen = new HashSet<>();
                List<String> matched = new ArrayList<>();
                while(m.find()) {
                    String href = m.group(1).trim();
                    String lower = href.toLowerCase();
                    if(lower.matches(".*\\.(png|jpg|jpeg|gif|svg|ico|css|js|woff|woff2|ttf|eot)(\\?.*)?$")) continue;
                    if(JOB_URL_PATTERN.matcher(href).find() && seen.add(lower)) {
                        matched.add(href);
                    }
                }
                System.out.println("  Matches: " + matched.size());
                if(!matched.isEmpty()) {
                    System.out.println("  Samples: " + matched.subList(0, Math.min(3, matched.size())));
                }
            } catch(Exception e) {
                System.out.println("  Error: " + e.getMessage());
            }
        }
    }
}
