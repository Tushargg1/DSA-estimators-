package com.dsatracker.service;

import com.dsatracker.adapter.RawSubmission;
import com.dsatracker.model.Platform;
import com.dsatracker.model.Submission;
import com.dsatracker.model.User;
import com.dsatracker.repository.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the transactional write boundary for one platform's onboarding history.
 * Calls from {@link BackfillService} cross the Spring proxy, so all rows for a
 * platform commit or roll back together while failures remain isolated between
 * platforms.
 */
@Service
public class BackfillPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(BackfillPersistenceService.class);

    private final SubmissionRepository submissionRepository;

    public BackfillPersistenceService(SubmissionRepository submissionRepository) {
        this.submissionRepository = submissionRepository;
    }

    /** Persists one platform history atomically; historical rows never score. */
    @Transactional
    public void persistBackfill(User user, Platform platform, List<RawSubmission> rawSubmissions) {
        if (rawSubmissions.isEmpty()) {
            log.debug("persistBackfill: no {} rows to persist for user id={}", platform, user.getId());
            return;
        }

        Long userId = user.getId();
        List<RawSubmission> ordered = new ArrayList<>(rawSubmissions);
        ordered.sort(Comparator.comparing(RawSubmission::solvedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Set<String> seenProblemIds = new HashSet<>();
        List<Submission> toInsert = new ArrayList<>();
        int skipped = 0;
        for (RawSubmission raw : ordered) {
            String problemId = raw.problemId();
            if (submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                    userId, platform, problemId, raw.solvedAt())) {
                skipped++;
                seenProblemIds.add(problemId);
                continue;
            }

            boolean firstAttempt = seenProblemIds.add(problemId)
                    && !submissionRepository.existsByUserIdAndPlatformAndProblemId(
                            userId, platform, problemId);

            Submission submission = new Submission();
            submission.setUserId(userId);
            submission.setPlatform(platform);
            submission.setProblemId(problemId);
            submission.setProblemName(raw.problemName());
            submission.setDifficulty(raw.difficulty());
            submission.setTags(raw.tags());
            submission.setSolvedAtUtc(raw.solvedAt());
            submission.setFirstAttempt(firstAttempt);
            submission.setCountedForTarget(false);
            submission.setCreatedAt(Instant.now());
            toInsert.add(submission);
        }

        if (!toInsert.isEmpty()) {
            submissionRepository.saveAll(toInsert);
        }

        log.info("persistBackfill: user id={}, platform={}, inserted {} row(s), skipped {} duplicate(s)",
                userId, platform, toInsert.size(), skipped);
    }
}
