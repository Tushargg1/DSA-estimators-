
import java.io.*;
import java.net.URI;
import java.net.http.*;

public class TestCareersAtTechApi {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://careersat.tech/api/jobs")) // Just guessing the API route
            .header("User-Agent", "Mozilla/5.0")
            .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Status: " + response.statusCode());
        System.out.println("Body: " + response.body().substring(0, Math.min(200, response.body().length())));
    }
}

