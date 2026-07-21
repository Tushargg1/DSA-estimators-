package com.dsatracker.github;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class GitHubExportPersistenceService {
    private final GitHubExportJobRepository jobs;
    private final GitHubSolutionCaptureRepository captures;
    private final GitHubConnectionRepository connections;
    private final GitHubProperties properties;

    public GitHubExportPersistenceService(GitHubExportJobRepository jobs,
                                          GitHubSolutionCaptureRepository captures,
                                          GitHubConnectionRepository connections,
                                          GitHubProperties properties) {
        this.jobs = jobs;
        this.captures = captures;
        this.connections = connections;
        this.properties = properties;
    }

    public record GitHubClaim(Long jobId, String token) { }
    public record GitHubWork(Long jobId, String claimToken,
                             GitHubSolutionCapture capture, Long installationId,
                             String repositoryFullName, String defaultBranch) {
        public boolean repositoryReady() {
            return installationId != null && repositoryFullName != null && defaultBranch != null;
        }
    }

    @Transactional
    public List<GitHubClaim> claimDue() {
        Instant leaseUntil = Instant.now().plus(properties.getPoll().getLeaseDuration());
        List<GitHubClaim> result = new ArrayList<>();
        for (Long id : jobs.findDueIdsForUpdate(properties.getPoll().getBatchSize())) {
            GitHubExportJob job = jobs.findById(id).orElseThrow();
            String token = UUID.randomUUID().toString();
            job.claim(leaseUntil, token);
            jobs.save(job);
            result.add(new GitHubClaim(id, token));
        }
        return List.copyOf(result);
    }

    @Transactional
    public Optional<GitHubWork> prepare(GitHubClaim claim) {
        GitHubExportJob job = jobs.findByIdForUpdate(claim.jobId()).orElse(null);
        if (job == null || job.getStatus() != GitHubExportStatus.PROCESSING
                || !claim.token().equals(job.getClaimToken())) return Optional.empty();
        job.claim(Instant.now().plus(properties.getPoll().getLeaseDuration()), claim.token());
        jobs.save(job);
        GitHubSolutionCapture capture = captures.findById(job.getCaptureId()).orElse(null);
        if (capture == null) return Optional.empty();
        GitHubConnection connection = connections.findById(capture.getUserId()).orElse(null);
        return Optional.of(new GitHubWork(job.getId(), claim.token(), capture,
                connection == null ? null : connection.getInstallationId(),
                connection == null ? null : connection.getRepositoryFullName(),
                connection == null ? null : connection.getDefaultBranch()));
    }

    @Transactional
    public void deferNoRepository(GitHubWork work) {
        GitHubExportJob job = claimedForUpdate(work);
        if (job == null) return;
        job.defer(Instant.now().plus(properties.getPoll().getRetryBase()));
        jobs.save(job);
    }

    @Transactional
    public void succeed(GitHubWork work) {
        GitHubExportJob job = claimedForUpdate(work);
        if (job == null) return;
        GitHubSolutionCapture capture = captures.findById(job.getCaptureId()).orElseThrow();
        capture.clearSourceCode();
        job.succeed();
        captures.save(capture);
        jobs.save(job);
    }

    @Transactional
    public void fail(GitHubWork work, Exception failure) {
        GitHubExportJob job = claimedForUpdate(work);
        if (job == null) return;
        Duration base = properties.getPoll().getRetryBase();
        long multiplier = 1L << Math.min(job.getAttempts(), 6);
        Duration delay;
        try {
            delay = base.multipliedBy(multiplier);
        } catch (ArithmeticException ex) {
            delay = properties.getPoll().getRetryMax();
        }
        if (delay.compareTo(properties.getPoll().getRetryMax()) > 0) {
            delay = properties.getPoll().getRetryMax();
        }
        job.fail(safeError(failure), Instant.now().plus(delay));
        jobs.save(job);
    }

    private GitHubExportJob claimedForUpdate(GitHubWork work) {
        GitHubExportJob job = jobs.findByIdForUpdate(work.jobId()).orElse(null);
        if (job == null || job.getStatus() != GitHubExportStatus.PROCESSING
                || !work.claimToken().equals(job.getClaimToken())) return null;
        return job;
    }

    private static String safeError(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        message = message.replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        String result = "GitHub export failed: " + message;
        return result.length() <= 500 ? result : result.substring(0, 500);
    }
}
