
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.util.regex.*;

public class TestCareersAtTech {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://careersat.tech/jobs"))
            .header("User-Agent", "Mozilla/5.0")
            .GET().build();
        String html = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        Pattern p = Pattern.compile("href\\s*=\\s*[\"\\u0027]([^\"\\u0027]{10,2048})[\"\\u0027]", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        int count = 0;
        while(m.find()) {
            if(m.group(1).contains("job") || m.group(1).contains("career")) {
                System.out.println("Matched: " + m.group(1));
            }
            count++;
        }
        System.out.println("Total links: " + count);
    }
}

