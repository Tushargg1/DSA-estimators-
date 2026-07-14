package com.dsatracker.service;

import com.dsatracker.adapter.RawSubmission;
import com.dsatracker.adapter.SubmissionFetcher;
import com.dsatracker.adapter.exception.RateLimitException;
import com.dsatracker.adapter.exception.ScrapeException;
import com.dsatracker.dto.LeaderboardUpdate;
import com.dsatracker.model.GroupMember;
import com.dsatracker.model.Platform;
import com.dsatracker.model.PollStatus;
import com.dsatracker.model.User;
import com.dsatracker.repository.DailyCountRepository;
import com.dsatracker.repository.GroupMemberRepository;
import com.dsatracker.repository.PollStatusRepository;
import com.dsatracker.repository.SubmissionRepository;
import com.dsatracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls every onboarded user's linked platforms with per-user/platform failure
 * isolation. Persistence is delegated to {@link PollingPersistenceService},
 * whose proxy-crossing transaction atomically writes submissions and rollups.
 */
@Service
public class PollingService {

    private static final Logger log = LoggerFactory.getLogger(PollingService.class);

    static final long POLL_INTERVAL_MS = 300_000L;
    static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final PollStatusRepository pollStatusRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final PollingPersistenceService persistenceService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Map<Platform, SubmissionFetcher> fetchersByPlatform;
    private final Clock clock;
    private final Duration rateLimitCooldown;
    private final Map<CooldownKey, Instant> cooldownUntil = new ConcurrentHashMap<>();

    private record CooldownKey(Long userId, Platform platform) {
        private CooldownKey {
            Objects.requireNonNull(platform, "platform");
        }
    }

    @Autowired
    public PollingService(UserRepository userRepository,
                          PollStatusRepository pollStatusRepository,
                          GroupMemberRepository groupMemberRepository,
                          PollingPersistenceService persistenceService,
                          SimpMessagingTemplate messagingTemplate,
                          List<SubmissionFetcher> fetchers,
                          @Value("${polling.rate-limit-cooldown-minutes:15}") long cooldownMinutes) {
        this(userRepository, pollStatusRepository, groupMemberRepository, persistenceService,
                messagingTemplate, fetchers, Clock.systemUTC(), Duration.ofMinutes(cooldownMinutes));
    }

    /** Test constructor retaining direct repository wiring without a Spring context. */
    PollingService(UserRepository userRepository,
                   SubmissionRepository submissionRepository,
                   DailyCountRepository dailyCountRepository,
                   PollStatusRepository pollStatusRepository,
                   GroupMemberRepository groupMemberRepository,
                   StreakService streakService,
                   SimpMessagingTemplate messagingTemplate,
                   List<SubmissionFetcher> fetchers,
                   Clock clock,
                   Duration rateLimitCooldown) {
        this(userRepository, pollStatusRepository, groupMemberRepository,
                new PollingPersistenceService(submissionRepository, dailyCountRepository, streakService),
                messagingTemplate, fetchers, clock, rateLimitCooldown);
    }

    private PollingService(UserRepository userRepository,
                           PollStatusRepository pollStatusRepository,
                           GroupMemberRepository groupMemberRepository,
                           PollingPersistenceService persistenceService,
                           SimpMessagingTemplate messagingTemplate,
                           List<SubmissionFetcher> fetchers,
                           Clock clock,
                           Duration rateLimitCooldown) {
        this.userRepository = userRepository;
        this.pollStatusRepository = pollStatusRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.persistenceService = persistenceService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
        this.rateLimitCooldown = rateLimitCooldown;
        this.fetchersByPlatform = new EnumMap<>(Platform.class);
        for (SubmissionFetcher fetcher : fetchers) {
            SubmissionFetcher existing = fetchersByPlatform.put(fetcher.platform(), fetcher);
            if (existing != null) {
                throw new IllegalStateException(
                        "Multiple SubmissionFetcher beans registered for platform "
                        + fetcher.platform() + ": " + existing.getClass().getName()
                        + " and " + fetcher.getClass().getName());
            }
        }
    }
    @Scheduled(fixedRate = POLL_INTERVAL_MS)
    public void pollAllUsers() {
        List<User> users = userRepository.findAllByOnboardingCompleteTrue();
        log.info("Polling cycle starting for {} onboarded user(s)", users.size());
        for (User user : users) {
            for (Map.Entry<Platform, String> linked : linkedPlatforms(user).entrySet()) {
                pollUserPlatform(user, linked.getKey(), linked.getValue());
            }
        }
        log.info("Polling cycle finished for {} onboarded user(s)", users.size());
    }

    private Map<Platform, String> linkedPlatforms(User user) {
        Map<Platform, String> linked = new EnumMap<>(Platform.class);
        putIfLinked(linked, Platform.LEETCODE, user.getLeetcodeUsername());
        putIfLinked(linked, Platform.CODEFORCES, user.getCodeforcesUsername());
        putIfLinked(linked, Platform.GFG, user.getGfgUsername());
        return linked;
    }

    private static void putIfLinked(Map<Platform, String> target, Platform platform, String username) {
        if (username != null && !username.isBlank()) {
            target.put(platform, username);
        }
    }

    void pollUserPlatform(User user, Platform platform, String username) {
        SubmissionFetcher fetcher = fetchersByPlatform.get(platform);
        if (fetcher == null) {
            log.warn("No adapter registered for platform {}, cannot poll user id={}",
                    platform, user.getId());
            return;
        }
        if (isInCooldown(user.getId(), platform)) {
            log.info("Skipping poll for platform={} user id={} — within rate-limit cooldown until {}",
                    platform, user.getId(), cooldownUntil.get(new CooldownKey(user.getId(), platform)));
            return;
        }

        try {
            List<RawSubmission> recent = fetcher.fetchRecent(username);
            log.info("Fetched {} recent {} submission(s) for user id={} (username='{}')",
                    recent.size(), platform, user.getId(), username);

            // This is a cross-bean proxy call. A normal return means the database
            // transaction committed, so no delta can describe rolled-back data.
            List<LeaderboardUpdate> updates =
                    persistenceService.processFetched(user, platform, recent);
            publishLeaderboardUpdates(user, updates);
            recordPollSuccess(platform);
        } catch (RateLimitException e) {
            Instant until = clock.instant().plus(rateLimitCooldown);
            cooldownUntil.put(new CooldownKey(user.getId(), platform), until);
            log.warn("Rate-limited polling platform={} user id={} at {} — cooling down until {}: {}",
                    platform, user.getId(), clock.instant(), until, e.getMessage());
            recordPollFailure(platform, describe(e));
        } catch (ScrapeException e) {
            if (platform == Platform.GFG) {
                log.error("GFG_PARSE_FAILURE polling platform={} user id={} at {}: {}",
                        platform, user.getId(), clock.instant(), e.getMessage(), e);
            } else {
                log.error("Scrape failure polling platform={} user id={} at {}: {}",
                        platform, user.getId(), clock.instant(), e.getMessage(), e);
            }
            recordPollFailure(platform, describe(e));
        } catch (RuntimeException e) {
            log.error("Failed polling platform={} user id={} at {}: {}",
                    platform, user.getId(), clock.instant(), e.getMessage(), e);
            recordPollFailure(platform, describe(e));
        }
    }
    private boolean isInCooldown(Long userId, Platform platform) {
        CooldownKey key = new CooldownKey(userId, platform);
        Instant until = cooldownUntil.get(key);
        if (until == null) {
            return false;
        }
        if (clock.instant().isBefore(until)) {
            return true;
        }
        cooldownUntil.remove(key, until);
        return false;
    }

    private void recordPollSuccess(Platform platform) {
        PollStatus status = loadOrCreatePollStatus(platform);
        status.setLastSuccessAt(clock.instant());
        pollStatusRepository.save(status);
    }

    private void recordPollFailure(Platform platform, String reason) {
        PollStatus status = loadOrCreatePollStatus(platform);
        status.setLastFailureAt(clock.instant());
        status.setLastFailureReason(reason);
        pollStatusRepository.save(status);
    }

    private PollStatus loadOrCreatePollStatus(Platform platform) {
        return pollStatusRepository.findById(platform).orElseGet(() -> {
            PollStatus status = new PollStatus();
            status.setPlatform(platform);
            return status;
        });
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getClass().getSimpleName() + ": " + message;
    }

    private void publishLeaderboardUpdates(User user, List<LeaderboardUpdate> updates) {
        if (updates.isEmpty()) {
            return;
        }
        try {
            List<GroupMember> memberships = groupMemberRepository.findByIdUserId(user.getId());
            for (LeaderboardUpdate update : updates) {
                for (GroupMember membership : memberships) {
                    Long groupId = membership.getId().getGroupId();
                    String destination = "/topic/group/" + groupId;
                    messagingTemplate.convertAndSend(destination, update);
                    log.info("Published leaderboard update to {} for user id={} "
                                    + "(problem='{}', newDailyCount={})",
                            destination, user.getId(), update.problemName(), update.newDailyCount());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to publish leaderboard update(s) for user id={} at {}: {}",
                    user.getId(), clock.instant(), e.getMessage(), e);
        }
    }
}
