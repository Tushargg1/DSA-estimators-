
import java.io.*;
import java.util.regex.*;

public class TestScrape {
    public static void main(String[] args) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("https://careersat.tech/jobs"))
            .header("User-Agent", "Mozilla/5.0")
            .build();
        String html = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        Pattern p = Pattern.compile("href\\s*=\\s*[\"\\u0027]([^\"\\u0027]{10,2048})[\"\\u0027]", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        int count = 0;
        while(m.find() && count < 10) {
            System.out.println(m.group(1));
            count++;
        }
    }
}

