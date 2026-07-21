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
        String patternSlug = classifier.classify(
                command.platform(), command.problemUrl(), command.tags());
        int inserted = captures.insertIfAbsent(userId, command.platform().name(),
                command.problemId(), command.problemName(), command.problemUrl(),
                command.language(), command.difficulty(), tagsJson, patternSlug,
                command.source(), command.solvedAt());
        GitHubSolutionCapture capture = captures
                .findByUserIdAndPlatformAndProblemId(
                        userId, command.platform(), command.problemId())
                .orElseThrow(() -> new IllegalStateException("Capture insert failed"));
        jobs.insertIfAbsent(capture.getId());
        GitHubExportJob job = jobs.findByCaptureId(capture.getId())
                .orElseThrow(() -> new IllegalStateException("Export job insert failed"));
        return new GitHubDtos.CaptureResponse(capture.getId(), inserted == 0, job.getStatus());
    }
}
