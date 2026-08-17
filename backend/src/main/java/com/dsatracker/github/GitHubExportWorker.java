package com.dsatracker.github;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GitHubExportWorker {
    private final GitHubExportPersistenceService persistence;
    private final GitHubApiClient api;
    private final GitHubClassifier classifier;
    private final GitHubMarkdown markdown;
    private final GitHubProperties properties;

    public GitHubExportWorker(GitHubExportPersistenceService persistence,
                              GitHubApiClient api, GitHubClassifier classifier,
                              GitHubMarkdown markdown, GitHubProperties properties) {
        this.persistence = persistence;
        this.api = api;
        this.classifier = classifier;
        this.markdown = markdown;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${github.poll.fixed-delay:30000}")
    public void exportDueSolutions() {
        if (!properties.configured() || !properties.isDirectExportEnabled()) return;
        for (GitHubExportPersistenceService.GitHubClaim claim : persistence.claimDue()) {
            GitHubExportPersistenceService.GitHubWork work = persistence.prepare(claim)
                    .orElse(null);
            if (work == null) continue;
            if (!work.repositoryReady()) {
                persistence.deferNoRepository(work);
                continue;
            }
            try {
                String path = classifier.path(work.capture());
                String content = markdown.render(work.capture());
                api.putMarkdown(work.installationId(), work.repositoryFullName(),
                        work.defaultBranch(), path, content, commitMessage(work.capture()));
                persistence.succeed(work);
            } catch (Exception ex) {
                persistence.fail(work, ex);
            }
        }
    }

    private static String commitMessage(GitHubSolutionCapture capture) {
        String name = capture.getProblemName().replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        if (name.length() > 150) name = name.substring(0, 150);
        return "Export solution: " + name;
    }
}
