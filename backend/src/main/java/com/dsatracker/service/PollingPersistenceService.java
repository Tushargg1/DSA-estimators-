package com.dsatracker.service;

import com.dsatracker.adapter.RawSubmission;
import com.dsatracker.dto.LeaderboardUpdate;
import com.dsatracker.model.DailyCount;
import com.dsatracker.model.DailyCountId;
import com.dsatracker.model.Platform;
import com.dsatracker.model.Submission;
import com.dsatracker.model.User;
import com.dsatracker.repository.DailyCountRepository;
import com.dsatracker.repository.SubmissionRepository;
import com.dsatracker.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Transactional persistence for one fetched user/platform batch. Submission
 * inserts and daily rollups commit atomically. Returned WebSocket deltas are
 * published by {@link PollingService} only after this proxy call commits.
 */
@Service
public class PollingPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PollingPersistenceService.class);

    private final SubmissionRepository submissionRepository;
    private final DailyCountRepository dailyCountRepository;
    private final StreakService streakService;

    public PollingPersistenceService(SubmissionRepository submissionRepository,
                                     DailyCountRepository dailyCountRepository,
                                     StreakService streakService) {
        this.submissionRepository = submissionRepository;
        this.dailyCountRepository = dailyCountRepository;
        this.streakService = streakService;
    }

    @Transactional
    public List<LeaderboardUpdate> processFetched(User user, Platform platform,
                                                   List<RawSubmission> rawSubmissions) {
        List<Submission> newlyCounted = persistNewSubmissions(user, platform, rawSubmissions);
        return updateDailyCounts(user, newlyCounted);
    }
    private List<Submission> persistNewSubmissions(User user, Platform platform,
                                                    List<RawSubmission> rawSubmissions) {
        if (rawSubmissions.isEmpty()) {
            return List.of();
        }

        Long userId = user.getId();
        List<RawSubmission> ordered = new ArrayList<>(rawSubmissions);
        ordered.sort(Comparator.comparing(RawSubmission::solvedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Set<String> seenProblemIds = new HashSet<>();
        List<Submission> toInsert = new ArrayList<>();
        List<Submission> counted = new ArrayList<>();
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
            // A failed platform backfill must not let old history score when it is
            // fetched by a later poll. The immutable signup timestamp is the
            // tracking cutoff; missing timestamps fail closed rather than score.
            boolean countedForTarget = firstAttempt
                    && isAtOrAfterTrackingStart(raw.solvedAt(), user.getCreatedAt());

            Submission submission = new Submission();

            submission.setUserId(userId);
            submission.setPlatform(platform);
            submission.setProblemId(problemId);
            submission.setProblemName(raw.problemName());
            submission.setDifficulty(raw.difficulty());
            submission.setTags(raw.tags());
            submission.setSolvedAtUtc(raw.solvedAt());
            submission.setFirstAttempt(firstAttempt);
            submission.setCountedForTarget(countedForTarget);
            submission.setCreatedAt(Instant.now());
            toInsert.add(submission);
            if (countedForTarget) {
                counted.add(submission);
            }
        }

        if (!toInsert.isEmpty()) {
            submissionRepository.saveAll(toInsert);
        }
        log.info("poll persist: user id={}, platform={}, inserted {} row(s) ({} counted), skipped {} duplicate(s)",
                userId, platform, toInsert.size(), counted.size(), skipped);
        return counted;
    }

    private static boolean isAtOrAfterTrackingStart(Instant solvedAt, Instant trackingStartedAt) {
        return solvedAt != null && trackingStartedAt != null
                && !solvedAt.isBefore(trackingStartedAt);
    }

    private List<LeaderboardUpdate> updateDailyCounts(User user, List<Submission> newlyCounted) {
        if (newlyCounted.isEmpty()) {
            return List.of();
        }

        Long userId = user.getId();
        Map<LocalDate, Integer> deltaByDay = new LinkedHashMap<>();
        for (Submission submission : newlyCounted) {
            LocalDate dateIst = TimeUtil.toIstDate(submission.getSolvedAtUtc());
            deltaByDay.merge(dateIst, 1, Integer::sum);
        }

        Map<LocalDate, Integer> finalCountByDay = new HashMap<>();
        for (Map.Entry<LocalDate, Integer> entry : deltaByDay.entrySet()) {
            LocalDate dateIst = entry.getKey();
            int delta = entry.getValue();
            Optional<DailyCount> existing =
                    dailyCountRepository.findByIdUserIdAndIdDateIst(userId, dateIst);
            DailyCount daily = existing.orElseGet(
                    () -> new DailyCount(new DailyCountId(userId, dateIst), 0, false));
            int newCount = daily.getCount() + delta;
            daily.setCount(newCount);
            daily.setTargetHit(streakService.isTargetHit(newCount, user.getDailyTarget()));
            dailyCountRepository.save(daily);
            finalCountByDay.put(dateIst, newCount);
            log.info("daily_counts upsert: user id={}, date_ist={}, +{} -> count={}, target_hit={}",
                    userId, dateIst, delta, newCount, daily.isTargetHit());
        }

        List<LeaderboardUpdate> updates = new ArrayList<>(newlyCounted.size());
        for (Submission submission : newlyCounted) {
            LocalDate dateIst = TimeUtil.toIstDate(submission.getSolvedAtUtc());
            updates.add(LeaderboardUpdate.from(
                    user, submission, finalCountByDay.getOrDefault(dateIst, 0)));
        }
        return updates;
    }
}
