
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.util.regex.*;

public class TestJio {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://careers.jio.com/frmfuncwisejob.aspx?func=098qkj0vwzk%3d&desc=tBOU2f2ubJIKJIaEorlljoC0j8hJb9P7"))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();
        System.out.println("Status: " + response.statusCode());
        System.out.println("HTML length: " + html.length());
        
        Pattern HREF_PATTERN = Pattern.compile("href\\s*=\\s*[\"\\u0027]([^\"\\u0027]{10,2048})[\"\\u0027]", Pattern.CASE_INSENSITIVE);
        Pattern JOB_URL_PATTERN = Pattern.compile("(/job|/career|/apply|/position|/opening|/vacanc|/req|/role|/opportunity|/posting|/detail|frmfuncwisejob)", Pattern.CASE_INSENSITIVE);
        Matcher m = HREF_PATTERN.matcher(html);
        int count = 0;
        int matched = 0;
        while(m.find()) {
            count++;
            String href = m.group(1);
            if(JOB_URL_PATTERN.matcher(href).find()) {
                System.out.println("MATCHED: " + href);
                matched++;
            }
        }
        System.out.println("Total links: " + count + ", Matched job links: " + matched);
    }
}

