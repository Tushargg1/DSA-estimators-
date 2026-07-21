package com.dsatracker.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class GitHubApiClient {
    private static final String ACCEPT = "application/vnd.github+json";
    private static final String API_VERSION = "2022-11-28";
    private final GitHubProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public GitHubApiClient(GitHubProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record GitHubInstallationInfo(Long id, Long accountId, String accountLogin) { }
    public record GitHubRepositoryInfo(Long id, String fullName, String defaultBranch) { }

    public GitHubInstallationInfo inspectInstallation(Long installationId) throws Exception {
        // Exchanging a token verifies this installation is accessible to this App.
        installationToken(installationId);
        JsonNode root = send("GET", "app/installations/" + installationId,
                appAuthorization(), null);
        Long returnedId = requiredLong(root, "id");
        JsonNode account = root.path("account");
        return new GitHubInstallationInfo(returnedId, requiredLong(account, "id"),
                requiredText(account, "login"));
    }

    public List<GitHubRepositoryInfo> listRepositories(Long installationId) throws Exception {
        String token = installationToken(installationId);
        List<GitHubRepositoryInfo> result = new ArrayList<>();
        int page = 1;
        int totalCount;
        do {
            String endpoint = "installation/repositories?per_page=100"
                    + (page == 1 ? "" : "&page=" + page);
            JsonNode root = send("GET", endpoint, "Bearer " + token, null);
            JsonNode repositories = root.path("repositories");
            if (!repositories.isArray()) {
                throw new IOException("GitHub response omitted repositories");
            }
            int before = result.size();
            for (JsonNode repository : repositories) {
                result.add(new GitHubRepositoryInfo(requiredLong(repository, "id"),
                        requiredText(repository, "full_name"),
                        requiredText(repository, "default_branch")));
            }
            totalCount = root.path("total_count").asInt(result.size());
            page++;
            if (result.size() == before) break;
        } while (result.size() < totalCount);
        return List.copyOf(result);
    }

    public void putMarkdown(Long installationId, String repositoryFullName,
                            String defaultBranch, String path, String markdown,
                            String commitMessage) throws Exception {
        String token = installationToken(installationId);
        String endpoint = "repos/" + encodePath(repositoryFullName)
                + "/contents/" + encodePath(path);
        String content = Base64.getEncoder().encodeToString(
                markdown.getBytes(StandardCharsets.UTF_8));
        String body = mapper.writeValueAsString(Map.of(
                "message", commitMessage,
                "content", content,
                "branch", defaultBranch));
        try {
            send("PUT", endpoint, "Bearer " + token, body);
        } catch (GitHubApiException ex) {
            // A timed-out prior attempt may already have created this capture-specific path.
            if (ex.statusCode == 422) {
                JsonNode existing = send("GET", endpoint + "?ref="
                        + encodeQuery(defaultBranch), "Bearer " + token, null);
                if (existing.hasNonNull("sha")) return;
            }
            throw ex;
        }
    }

    private String installationToken(Long installationId) throws Exception {
        JsonNode root = send("POST", "app/installations/" + installationId
                + "/access_tokens", appAuthorization(), "{}");
        return requiredText(root, "token");
    }

    private JsonNode send(String method, String relativePath, String authorization,
                          String body) throws Exception {
        URI uri = properties.apiBaseUri().resolve(relativePath);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", ACCEPT)
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "DSA-Tracker-GitHub-Exporter/1.0")
                .header("Authorization", authorization);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GitHubApiException(response.statusCode());
        }
        if (response.body() == null || response.body().isBlank()) return mapper.createObjectNode();
        return mapper.readTree(response.body());
    }

    private String appAuthorization() throws Exception {
        GitHubProperties.GitHubApp app = properties.getApp();
        Instant now = Instant.now();
        String header = base64Url(mapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String payload = base64Url(mapper.writeValueAsBytes(Map.of(
                "iat", now.minusSeconds(30).getEpochSecond(),
                "exp", now.plusSeconds(540).getEpochSecond(),
                "iss", app.getId())));
        String signingInput = header + "." + payload;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey(app.getPrivateKeyBase64()));
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return "Bearer " + signingInput + "." + base64Url(signer.sign());
    }

    private static PrivateKey privateKey(String base64) throws Exception {
        byte[] encoded = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String encodePath(String value) {
        String[] segments = value.split("/", -1);
        List<String> encoded = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Unsafe GitHub path");
            }
            encoded.add(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return String.join("/", encoded);
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class GitHubApiException extends IOException {
        private final int statusCode;

        private GitHubApiException(int statusCode) {
            super("GitHub API request failed with status " + statusCode);
            this.statusCode = statusCode;
        }
    }

    private static Long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IOException("GitHub response omitted " + field);
        }
        return value.longValue();
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException("GitHub response omitted " + field);
        }
        return value.asText();
    }
}
