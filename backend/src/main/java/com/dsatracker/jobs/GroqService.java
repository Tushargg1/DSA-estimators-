package com.dsatracker.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class GroqService {
    private static final Logger log = LoggerFactory.getLogger(GroqService.class);
    
    // Limits state (singleton service)
    private Integer limitRequestsLeft = null;
    private Integer limitTokensLeft = null;
    
    private final RestTemplate restTemplate = new RestTemplate();
    private final String API_URL = "https://api.groq.com/openai/v1/chat/completions";
    
    @org.springframework.beans.factory.annotation.Value("${groq.api.key:gsk_placeholder}")
    private String API_KEY;

    public boolean confirmJobMatch(String profileTitle, String profileKeywords, String jobTitle, String jobDescription) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(API_KEY);

            String prompt = String.format("Analyze this job posting.\\nJob Title: %s\\nJob Description: %s\\n\\nDoes this job strongly match a target role of '%s' (Keywords: %s) AND requires 0 years of experience (or is entry level)?\\nAnswer ONLY with the word YES or NO.", 
                jobTitle,
                truncate(jobDescription, 2000), // Prevent token overflow
                profileTitle,
                profileKeywords != null ? profileKeywords : "None");

            Map<String, Object> requestBody = Map.of(
                "model", "llama3-8b-8192",
                "messages", List.of(
                    Map.of("role", "system", "content", "You are an expert technical recruiter matching job descriptions to target profiles. Only output YES or NO."),
                    Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.0,
                "max_tokens", 5
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(API_URL, request, Map.class);
            
            // Update rate limit state
            HttpHeaders responseHeaders = response.getHeaders();
            updateLimits(responseHeaders);

            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) message.get("content");
                    if (content != null) {
                        return content.trim().toUpperCase().startsWith("YES");
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error calling Groq API: " + e.getMessage(), e);
            // If the API fails, we could default to true if we don't want to lose the job, 
            // or false if we strictly want confirmation. Let's return true as fallback.
            return true;
        }
        return false;
    }
    
    private void updateLimits(HttpHeaders headers) {
        try {
            String requestsLeft = headers.getFirst("x-ratelimit-remaining-requests");
            String tokensLeft = headers.getFirst("x-ratelimit-remaining-tokens");
            
            if (requestsLeft != null) {
                this.limitRequestsLeft = Integer.parseInt(requestsLeft);
            }
            if (tokensLeft != null) {
                this.limitTokensLeft = Integer.parseInt(tokensLeft);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Groq rate limits", e);
        }
    }
    
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    public Integer getLimitRequestsLeft() {
        return limitRequestsLeft;
    }

    public Integer getLimitTokensLeft() {
        return limitTokensLeft;
    }
}
