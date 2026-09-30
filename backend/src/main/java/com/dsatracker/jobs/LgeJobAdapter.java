package com.dsatracker.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Component
public class LgeJobAdapter implements JobPortalAdapter {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String name() {
        return "lge_global";
    }

    @Override
    public boolean supports(String url) {
        return url != null && url.contains("globalcareers.lge.com");
    }

    @Override
    public Chunk fetchChunk(JobSource source, int startIndex, int chunkSize) throws Exception {
        int page = (startIndex / chunkSize) + 1;
        String apiUrl = "https://globalcareers.lge.com/api/job/v1/jobs/?page=" + page + "&size=" + chunkSize;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new Exception("LGE API failed with status " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode data = root.path("data");
        JsonNode list = data.path("list");
        
        List<ScrapedJob> jobs = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode item : list) {
                String id = item.path("id").asText();
                String title = item.path("title").asText();
                String url = "https://globalcareers.lge.com/jobs/" + id;
                String location = item.path("countryNm").asText(null);
                if (location == null || location.isEmpty()) {
                    location = item.path("locationNm").asText(null);
                }
                String dept = item.path("jobFamilyNm").asText(null);
                
                jobs.add(new ScrapedJob(id, title, url, dept, location, null, null, null, null, null));
            }
        }
        
        int total = data.path("total").asInt(0);
        boolean hasMore = (startIndex + jobs.size()) < total;
        
        return new Chunk(jobs, hasMore);
    }
}
