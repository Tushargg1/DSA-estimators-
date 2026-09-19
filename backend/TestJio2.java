
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.util.regex.*;

public class TestJio2 {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://careers.jio.com/frmfuncwisejob.aspx?func=098qkj0vwzk%3d&desc=tBOU2f2ubJIKJIaEorlljoC0j8hJb9P7"))
            .header("User-Agent", "Mozilla/5.0")
            .GET().build();
        String html = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        Pattern p = Pattern.compile("href\\s*=\\s*[\"\\u0027]([^\"\\u0027]{10,2048})[\"\\u0027]", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        int c = 0;
        while(m.find() && c < 20) {
            System.out.println(m.group(1));
            c++;
        }
    }
}

