package com.dsatracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitHubCaptureService {
    private final GitHubConnectionRepository connections;
    private final GitHubSolutionCaptureRepository captures;
    private final GitHubExportJobRepository jobs;
    private final GitHubClassifier classifier;
    private final ObjectMapper mapper;

    public GitHubCaptureService(GitHubConnectionRepository connections,
                                GitHubSolutionCaptureRepository captures,
                                GitHubExportJobRepository jobs,
                                GitHubClassifier classifier, ObjectMapper mapper) {
        this.connections = connections;
        this.captures = captures;
        this.jobs = jobs;
        this.classifier = classifier;
        this.mapper = mapper;
    }

    public GitHubConnection authenticate(String plaintextToken) {
        if (plaintextToken == null || plaintextToken.isBlank()) return null;
        return connections.findByExtensionTokenHash(
                GitHubCrypto.sha256Hex(plaintextToken)).orElse(null);
    }

    @Transactional
    public GitHubDtos.CaptureResponse capture(
            Long userId, GitHubDtos.CaptureCommand command) {
        String tagsJson;
        try {
            tagsJson = command.tags() == null ? null : mapper.writeValueAsString(command.tags());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("tags are invalid");
        }
        verifySourceHash(command);
        String patternSlug = classifier.classify(
                command.platform(), command.problemUrl(), command.tags());
        int inserted = captures.insertIfAbsent(userId, command.platform().name(),
                command.problemId(), command.problemName(), command.problemUrl(),
                command.language(), command.difficulty(), tagsJson, patternSlug,
                command.source(), command.solvedAt());
        int updated = inserted == 0
                ? captures.updateIfChanged(userId, command.platform().name(),
                        command.problemId(), command.problemName(), command.problemUrl(),
                        command.language(), command.difficulty(), tagsJson, patternSlug,
                        command.source(), command.solvedAt())
                : 0;
        GitHubSolutionCapture capture = captures
                .findByUserIdAndPlatformAndProblemId(
                        userId, command.platform(), command.problemId())
                .orElseThrow(() -> new IllegalStateException("Capture upsert failed"));

        GitHubConnection connection = connections.findById(userId).orElse(null);
        GitHubExportStatus exportStatus = null;
        if (connection != null && connection.getRepositoryId() != null) {
            if (inserted > 0 || updated > 0) jobs.enqueue(capture.getId());
            exportStatus = jobs.findByCaptureId(capture.getId())
                    .map(GitHubExportJob::getStatus).orElse(null);
        }
        return new GitHubDtos.CaptureResponse(
                capture.getId(), inserted > 0, updated > 0, exportStatus);
    }

    private static void verifySourceHash(GitHubDtos.CaptureCommand command) {
        String actual = GitHubCrypto.sha256Hex(command.source());
        if (!actual.equalsIgnoreCase(command.sourceHash())) {
            throw new IllegalArgumentException("sourceHash does not match source");
        }
    }

    @Transactional(readOnly = true)
    public java.util.List<GitHubDtos.CaptureExportResponse> exports(Long userId) {
        return captures.findByUserIdOrderBySolvedAtUtcAsc(userId).stream()
                .map(capture -> new GitHubDtos.CaptureExportResponse(
                        capture.getId(), capture.getPlatform(), capture.getProblemId(),
                        capture.getProblemName(), capture.getProblemUrl(),
                        capture.getLanguage(), capture.getSourceCode(),
                        capture.getDifficulty(), capture.getTags(),
                        capture.getPatternSlug(), capture.getSolvedAtUtc()))
                .toList();
    }
}
