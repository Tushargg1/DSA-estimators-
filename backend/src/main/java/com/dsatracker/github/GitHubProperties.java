package com.dsatracker.github;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {
    private final GitHubApp app = new GitHubApp();
    private final GitHubPoll poll = new GitHubPoll();
    private String apiBaseUrl = "https://api.github.com";

    public GitHubApp getApp() { return app; }
    public GitHubPoll getPoll() { return poll; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String value) { this.apiBaseUrl = value; }
    public boolean configured() {
        return !blank(app.id) && !blank(app.slug) && !blank(app.privateKeyBase64);
    }
    public URI apiBaseUri() { return URI.create(apiBaseUrl.endsWith("/") ? apiBaseUrl : apiBaseUrl + "/"); }

    @PostConstruct
    void validate() {
        if (!"https".equalsIgnoreCase(apiBaseUri().getScheme())) {
            throw new IllegalArgumentException("github.api-base-url must use HTTPS");
        }
        if (poll.fixedDelay <= 0 || poll.batchSize < 1 || poll.batchSize > 100
                || !positive(poll.leaseDuration) || !positive(poll.retryBase)
                || !positive(poll.retryMax) || poll.retryMax.compareTo(poll.retryBase) < 0) {
            throw new IllegalArgumentException("GitHub poll settings are invalid");
        }
    }
    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public static class GitHubApp {
        private String id = "";
        private String slug = "";
        private String privateKeyBase64 = "";
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public String getPrivateKeyBase64() { return privateKeyBase64; }
        public void setPrivateKeyBase64(String value) { this.privateKeyBase64 = value; }
    }

    public static class GitHubPoll {
        private long fixedDelay = 30_000;
        private int batchSize = 10;
        private Duration leaseDuration = Duration.ofMinutes(5);
        private Duration retryBase = Duration.ofMinutes(1);
        private Duration retryMax = Duration.ofHours(1);
        public long getFixedDelay() { return fixedDelay; }
        public void setFixedDelay(long value) { this.fixedDelay = value; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { this.batchSize = value; }
        public Duration getLeaseDuration() { return leaseDuration; }
        public void setLeaseDuration(Duration value) { this.leaseDuration = value; }
        public Duration getRetryBase() { return retryBase; }
        public void setRetryBase(Duration value) { this.retryBase = value; }
        public Duration getRetryMax() { return retryMax; }
        public void setRetryMax(Duration value) { this.retryMax = value; }
    }
}
