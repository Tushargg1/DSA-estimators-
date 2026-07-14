package com.dsatracker.service;

import com.dsatracker.adapter.RawSubmission;
import com.dsatracker.adapter.SubmissionFetcher;
import com.dsatracker.config.AsyncConfig;
import com.dsatracker.model.Platform;
import com.dsatracker.model.User;
import com.dsatracker.repository.SubmissionRepository;
import com.dsatracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates one-time onboarding history imports. Each linked platform is
 * fetched and persisted independently, so one platform failure does not block
 * the others or onboarding completion. Platform writes are delegated to the
 * proxy-backed {@link BackfillPersistenceService} transaction boundary.
 */
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    private final UserRepository userRepository;
    private final BackfillPersistenceService persistenceService;
    private final Map<Platform, SubmissionFetcher> fetchersByPlatform;

    @Autowired
    public BackfillService(UserRepository userRepository,
                           BackfillPersistenceService persistenceService,
                           List<SubmissionFetcher> fetchers) {
        this.userRepository = userRepository;
        this.persistenceService = persistenceService;
        this.fetchersByPlatform = indexFetchers(fetchers);
    }

    /** Test constructor retaining direct repository wiring without Spring. */
    BackfillService(UserRepository userRepository,
                    SubmissionRepository submissionRepository,
                    List<SubmissionFetcher> fetchers) {
        this(userRepository, new BackfillPersistenceService(submissionRepository), fetchers);
    }

    private static Map<Platform, SubmissionFetcher> indexFetchers(List<SubmissionFetcher> fetchers) {
        Map<Platform, SubmissionFetcher> indexed = new EnumMap<>(Platform.class);
        for (SubmissionFetcher fetcher : fetchers) {
            SubmissionFetcher existing = indexed.put(fetcher.platform(), fetcher);
            if (existing != null) {
                throw new IllegalStateException(
                        "Multiple SubmissionFetcher beans registered for platform "
                        + fetcher.platform() + ": " + existing.getClass().getName()
                        + " and " + fetcher.getClass().getName());
            }
        }
        return indexed;
    }

    @Async(AsyncConfig.BACKFILL_EXECUTOR)
    public void runBackfill(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Backfill requested for unknown user id={}, skipping", userId);
            return;
        }

        log.info("Starting backfill for user id={} ({})", user.getId(), user.getName());
        Map<Platform, String> linkedUsernames = new EnumMap<>(Platform.class);
        putIfLinked(linkedUsernames, Platform.LEETCODE, user.getLeetcodeUsername());
        putIfLinked(linkedUsernames, Platform.CODEFORCES, user.getCodeforcesUsername());
        putIfLinked(linkedUsernames, Platform.GFG, user.getGfgUsername());

        List<Platform> succeeded = new ArrayList<>();
        List<Platform> failed = new ArrayList<>();
        for (Map.Entry<Platform, String> entry : linkedUsernames.entrySet()) {
            boolean ok = backfillPlatform(user, entry.getKey(), entry.getValue());
            (ok ? succeeded : failed).add(entry.getKey());
        }

        user.setOnboardingComplete(true);
        userRepository.save(user);
        log.info("Backfill complete for user id={}: onboarding_complete=true, succeeded={}, failed={}",
                user.getId(), succeeded, failed);
    }

    private static void putIfLinked(Map<Platform, String> target, Platform platform, String username) {
        if (username != null && !username.isBlank()) {
            target.put(platform, username);
        }
    }

    boolean backfillPlatform(User user, Platform platform, String username) {
        if (username == null || username.isBlank()) {
            log.debug("User id={} has no {} username linked, skipping", user.getId(), platform);
            return true;
        }
        SubmissionFetcher fetcher = fetchersByPlatform.get(platform);
        if (fetcher == null) {
            log.warn("No adapter registered for platform {}, cannot backfill user id={}",
                    platform, user.getId());
            return false;
        }

        try {
            List<RawSubmission> history = gatherHistory(fetcher, username);
            log.info("Gathered {} {} submission(s) for user id={} (username='{}')",
                    history.size(), platform, user.getId(), username);
            persistenceService.persistBackfill(user, platform, history);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Backfill failed for platform={} user={}: {}",
                    platform, user.getId(), ex.getMessage(), ex);
            return false;
        }
    }
    /**
     * Uses the adapter's deepest verified history path. Codeforces paginates its
     * official user.status API to exhaustion; LeetCode uses the verified recent
     * accepted-submission query because this codebase has no reliable cursor or
     * offset contract for that field; GFG remains limited to visible profile data.
     */
    List<RawSubmission> gatherHistory(SubmissionFetcher fetcher, String username) {
        return fetcher.fetchHistory(username);
    }

    /**
     * Compatibility seam used by existing database-free tests. The actual
     * transactional method belongs to a separate injected component, so the
     * production call path never relies on self-invocation.
     */
    void persistBackfill(User user, Platform platform, List<RawSubmission> rawSubmissions) {
        persistenceService.persistBackfill(user, platform, rawSubmissions);
    }
}
