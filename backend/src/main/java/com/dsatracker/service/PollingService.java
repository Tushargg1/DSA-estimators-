package com.dsatracker.service;

import com.dsatracker.adapter.RawSubmission;
import com.dsatracker.adapter.SubmissionFetcher;
import com.dsatracker.adapter.exception.RateLimitException;
import com.dsatracker.adapter.exception.ScrapeException;
import com.dsatracker.dto.LeaderboardUpdate;
import com.dsatracker.model.DailyCount;
import com.dsatracker.model.DailyCountId;
import com.dsatracker.model.GroupMember;
import com.dsatracker.model.Platform;
import com.dsatracker.model.PollStatus;
import com.dsatracker.model.Submission;
import com.dsatracker.model.User;
import com.dsatracker.repository.DailyCountRepository;
import com.dsatracker.repository.GroupMemberRepository;
import com.dsatracker.repository.PollStatusRepository;
import com.dsatracker.repository.SubmissionRepository;
import com.dsatracker.repository.UserRepository;
import com.dsatracker.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic polling job that pulls recent submissions for every onboarded user
 * across all of their linked platforms (Requirement 2).
 *
 * <h2>Scope of task 5.1 (scheduling + iteration skeleton)</h2>
 * The scheduled entry point and per-user / per-platform iteration:
 * <ul>
 *   <li>Runs {@link #pollAllUsers()} every 5 minutes via
 *       {@code @Scheduled(fixedRate = 300000)} (Requirement 2.1). Because a
 *       single scheduled tick visits each linked platform for each user at most
 *       once, the schedule itself guarantees Requirement 2.6 — no platform is
 *       polled for a given user more than once per 5 minutes.</li>
 *   <li>Loads only onboarded users
 *       ({@link UserRepository#findAllByOnboardingCompleteTrue()}) and, for each,
 *       iterates their linked platforms (non-null / non-blank usernames),
 *       selecting the matching {@link SubmissionFetcher} from an
 *       {@link EnumMap} built once from the injected adapter list — the same
 *       pattern used by {@link BackfillService}.</li>
 *   <li>Routes each linked platform to the {@link #pollUserPlatform} seam.</li>
 * </ul>
 *
 * <h2>Tasks 5.2&ndash;5.5 (implemented here)</h2>
 * {@link #pollUserPlatform(User, Platform, String)} now persists the fetched
 * window:
 * <ul>
 *   <li><b>5.2</b> — dedupe by {@code (user_id, platform, problem_id,
 *       solved_at_utc)} via
 *       {@link SubmissionRepository#existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc}
 *       and insert only genuinely new rows (Requirements 2.2, 2.3).</li>
 *   <li><b>5.3</b> — compute {@code is_first_attempt}: true iff the user has
 *       never solved {@code (user_id, platform, problem_id)} before (ignoring
 *       timestamp), accounting for multiple new solves of the same problem inside
 *       one poll batch by processing earliest-first and tracking seen problem ids
 *       (Requirements 3.1&ndash;3.3, design.md Property 2).</li>
 *   <li><b>5.4</b> — compute {@code counted_for_target = is_first_attempt &&
 *       user.isOnboardingComplete()}. The job only polls onboarded users, so this
 *       is effectively {@code is_first_attempt} here, but the onboarding gate is
 *       kept explicit to preserve design.md Property 4 (Requirement 3.5).</li>
 *   <li><b>5.5</b> — upsert {@code daily_counts} keyed by {@code (user_id,
 *       date_ist)} where {@code date_ist = TimeUtil.toIstDate(solved_at_utc)},
 *       grouping counted submissions by day so multiple new solves on the same
 *       IST day become one upsert with the right delta (Requirements 3.6,
 *       4.5).</li>
 * </ul>
 *
 * <h2>Seam remaining for tasks 5.6&ndash;5.8</h2>
 * <ul>
 *   <li><b>5.6</b> — per-user-per-platform try/catch isolation so one failure
 *       does not abort the whole job.</li>
 *   <li><b>5.7</b> — update {@code poll_status} (success/failure timestamps and
 *       reason).</li>
 *   <li><b>5.8</b> — rate-limit cooldown (skip a platform for a user for 15 min
 *       after a detected rate-limit response).</li>
 * </ul>
 */
@Service
public class PollingService {

    private static final Logger log = LoggerFactory.getLogger(PollingService.class);

    /** 5 minutes in milliseconds — the fixed polling cadence (Requirement 2.1 / 2.6). */
    static final long POLL_INTERVAL_MS = 300_000L;

    /** Default rate-limit cooldown window (Requirement 2.5): 15 minutes. */
    static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final SubmissionRepository submissionRepository;
    private final DailyCountRepository dailyCountRepository;
    private final PollStatusRepository pollStatusRepository;

    /**
     * Resolves the groups a user belongs to so a new counted submission can be
     * pushed to every {@code /topic/group/{groupId}} channel that user appears on
     * (task 9.2, Requirement 7.1).
     */
    private final GroupMemberRepository groupMemberRepository;

    /**
     * STOMP broker template used to publish {@link LeaderboardUpdate} deltas to
     * group topics (task 9.2). Publishing is best-effort: a messaging failure is
     * logged and swallowed so it can never abort persistence or the poll cycle
     * (see {@link #publishLeaderboardUpdates}).
     */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Owns the {@code target_hit} rule (task 7.1). The daily-count upsert routes
     * through {@link StreakService#isTargetHit(int, int)} so {@code target_hit} has
     * a single authoritative definition ({@code count >= daily_target},
     * Requirement 5.1) rather than an inline comparison here.
     */
    private final StreakService streakService;

    /** Adapter per platform, built once from all injected {@link SubmissionFetcher}s. */
    private final Map<Platform, SubmissionFetcher> fetchersByPlatform;

    /**
     * Clock used for all "now" reads (poll_status timestamps and rate-limit
     * cooldown windows). Defaults to {@link Clock#systemUTC()} in production and
     * is injectable so tests can drive the 15-minute cooldown window without
     * sleeping (task 5.8).
     */
    private final Clock clock;

    /** Configurable rate-limit cooldown window (task 5.8, Requirement 2.5). */
    private final Duration rateLimitCooldown;

    /**
     * In-memory rate-limit cooldowns keyed per {@code (userId, platform)} — the
     * finer-grained key called out in design.md ("skip that platform for that
     * user ... set a cooldown timestamp in memory"). The value is the instant at
     * which the cooldown expires; entries are lazily cleared once expired. A
     * {@link ConcurrentHashMap} keeps this safe if polling ever runs across
     * threads (task 5.8, Requirement 2.5).
     */
    private final Map<CooldownKey, Instant> cooldownUntil = new ConcurrentHashMap<>();

    /** Composite key for the {@link #cooldownUntil} map: one cooldown per (user, platform). */
    private record CooldownKey(Long userId, Platform platform) {
        private CooldownKey {
            Objects.requireNonNull(platform, "platform");
        }
    }

    /**
     * Spring injects every {@code @Component} {@link SubmissionFetcher}
     * (LeetCode, Codeforces, GFG) into this list; we index them by
     * {@link SubmissionFetcher#platform()} so a linked username can be routed to
     * the correct adapter without hard-coding concrete types (same pattern as
     * {@link BackfillService}).
     *
     * <p>The cooldown window is read from
     * {@code polling.rate-limit-cooldown-minutes} (default 15) so it stays
     * configurable per Requirement 2.5, and the clock defaults to
     * {@link Clock#systemUTC()}.
     */
    @Autowired
    public PollingService(UserRepository userRepository,
                          SubmissionRepository submissionRepository,
                          DailyCountRepository dailyCountRepository,
                          PollStatusRepository pollStatusRepository,
                          GroupMemberRepository groupMemberRepository,
                          StreakService streakService,
                          SimpMessagingTemplate messagingTemplate,
                          List<SubmissionFetcher> fetchers,
                          @Value("${polling.rate-limit-cooldown-minutes:15}") long cooldownMinutes) {
        this(userRepository, submissionRepository, dailyCountRepository, pollStatusRepository,
                groupMemberRepository, streakService, messagingTemplate, fetchers,
                Clock.systemUTC(), Duration.ofMinutes(cooldownMinutes));
    }

    /**
     * Full constructor exposing the {@link Clock} and cooldown {@link Duration}
     * for tests, so the rate-limit cooldown window (task 5.8) can be exercised
     * deterministically without real time passing.
     */
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
        this.userRepository = userRepository;
        this.submissionRepository = submissionRepository;
        this.dailyCountRepository = dailyCountRepository;
        this.pollStatusRepository = pollStatusRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.streakService = streakService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
        this.rateLimitCooldown = rateLimitCooldown;
        this.fetchersByPlatform = new EnumMap<>(Platform.class);
        for (SubmissionFetcher fetcher : fetchers) {
            SubmissionFetcher existing = fetchersByPlatform.put(fetcher.platform(), fetcher);
            if (existing != null) {
                // Two adapters claiming the same platform is a wiring bug — surface it loudly.
                throw new IllegalStateException(
                        "Multiple SubmissionFetcher beans registered for platform "
                        + fetcher.platform() + ": " + existing.getClass().getName()
                        + " and " + fetcher.getClass().getName());
            }
        }
    }

    /**
     * The scheduled polling tick. Runs every {@link #POLL_INTERVAL_MS} ms
     * (5 minutes) after the previous invocation started (Requirement 2.1).
     *
     * <p>Iterates all onboarded users and, for each, every linked platform. The
     * fixed cadence is what satisfies Requirement 2.6: a single tick touches each
     * (user, platform) pair at most once, so no platform is polled for a given
     * user more than once per 5 minutes.
     */
    @Scheduled(fixedRate = POLL_INTERVAL_MS)
    public void pollAllUsers() {
        List<User> users = userRepository.findAllByOnboardingCompleteTrue();
        log.info("Polling cycle starting for {} onboarded user(s)", users.size());

        for (User user : users) {
            for (Map.Entry<Platform, String> linked : linkedPlatforms(user).entrySet()) {
                // Task 5.6: pollUserPlatform isolates each (user, platform) with its
                // own try/catch, so one platform's failure cannot abort the cycle.
                pollUserPlatform(user, linked.getKey(), linked.getValue());
            }
        }

        log.info("Polling cycle finished for {} onboarded user(s)", users.size());
    }

    /**
     * Builds the {@code platform -> username} map of platforms this user has
     * actually linked (non-null / non-blank username). Unlinked platforms are
     * omitted so they are never polled.
     */
    private Map<Platform, String> linkedPlatforms(User user) {
        Map<Platform, String> linked = new EnumMap<>(Platform.class);
        putIfLinked(linked, Platform.LEETCODE, user.getLeetcodeUsername());
        putIfLinked(linked, Platform.CODEFORCES, user.getCodeforcesUsername());
        putIfLinked(linked, Platform.GFG, user.getGfgUsername());
        return linked;
    }

    /** Adds {@code platform -> username} to {@code target} only if the username is linked (non-blank). */
    private static void putIfLinked(Map<Platform, String> target, Platform platform, String username) {
        if (username != null && !username.isBlank()) {
            target.put(platform, username);
        }
    }

    /**
     * Polls a single platform for a single user: fetch the recent window, dedupe
     * and insert new rows (5.2), compute {@code is_first_attempt} (5.3) and
     * {@code counted_for_target} (5.4), and upsert {@code daily_counts} for the
     * newly counted rows (5.5).
     *
     * <p>Annotated {@code @Transactional} so the submission inserts and the
     * {@code daily_counts} upsert for one (user, platform) commit atomically — a
     * mid-write failure must not leave the rollup out of sync with the rows it
     * counts. (Note: {@code pollAllUsers} invokes this method on the same bean, so
     * as with {@link BackfillService#persistBackfill} the transactional proxy only
     * applies when invoked externally; the annotation documents the intended
     * boundary and takes effect for direct callers such as tests via a proxy.)
     *
     * @param user     the onboarded user being polled
     * @param platform the linked platform to poll
     * @param username the user's username/handle on that platform (non-blank)
     */
    void pollUserPlatform(User user, Platform platform, String username) {
        SubmissionFetcher fetcher = fetchersByPlatform.get(platform);
        if (fetcher == null) {
            log.warn("No adapter registered for platform {}, cannot poll user id={}",
                    platform, user.getId());
            return;
        }

        // Task 5.8: skip this (user, platform) while it is inside a rate-limit
        // cooldown window (Requirement 2.5). Expired cooldowns are cleared.
        if (isInCooldown(user.getId(), platform)) {
            log.info("Skipping poll for platform={} user id={} — within rate-limit cooldown until {}",
                    platform, user.getId(), cooldownUntil.get(new CooldownKey(user.getId(), platform)));
            return;
        }

        // Task 5.6: isolate this (user, platform). ANY failure (rate-limit,
        // scrape/parse, timeout, or any other RuntimeException) is logged with
        // platform + user + timestamp and swallowed so the polling cycle keeps
        // going for every other user/platform (Requirements 2.4, 9.1). On success
        // and failure we record poll_status (task 5.7, Requirement 9.2).
        try {
            List<RawSubmission> recent = fetcher.fetchRecent(username);
            log.info("Fetched {} recent {} submission(s) for user id={} (username='{}')",
                    recent.size(), platform, user.getId(), username);

            // Tasks 5.2–5.5: dedupe + insert, compute flags, roll counted rows into
            // daily_counts. Returns the WebSocket deltas to publish (task 9.2),
            // each carrying the accurate post-upsert daily count.
            List<LeaderboardUpdate> updates = processFetched(user, platform, recent);

            // Task 9.2: publish AFTER the transactional persistence above has
            // returned, so the DB write is never rolled back by a messaging
            // failure. publishLeaderboardUpdates swallows its own errors, so a
            // broken broker cannot abort the poll or skip the poll_status stamp.
            publishLeaderboardUpdates(user, updates);

            // Task 5.7: successful poll of this platform -> stamp last_success_at.
            recordPollSuccess(platform);
        } catch (RateLimitException e) {
            // Task 5.8: back this (user, platform) off for the cooldown window.
            Instant until = clock.instant().plus(rateLimitCooldown);
            cooldownUntil.put(new CooldownKey(user.getId(), platform), until);
            log.warn("Rate-limited polling platform={} user id={} at {} — cooling down until {}: {}",
                    platform, user.getId(), clock.instant(), until, e.getMessage());
            recordPollFailure(platform, describe(e));
        } catch (ScrapeException e) {
            // Task 5.6 / Requirement 9.1: GFG parse failures get an explicit
            // GFG_PARSE_FAILURE event; other scrape failures are logged plainly.
            if (platform == Platform.GFG) {
                log.error("GFG_PARSE_FAILURE polling platform={} user id={} at {}: {}",
                        platform, user.getId(), clock.instant(), e.getMessage(), e);
            } else {
                log.error("Scrape failure polling platform={} user id={} at {}: {}",
                        platform, user.getId(), clock.instant(), e.getMessage(), e);
            }
            recordPollFailure(platform, describe(e));
        } catch (RuntimeException e) {
            // Timeouts and any other unexpected error for one (user, platform) must
            // never abort the whole cycle.
            log.error("Failed polling platform={} user id={} at {}: {}",
                    platform, user.getId(), clock.instant(), e.getMessage(), e);
            recordPollFailure(platform, describe(e));
        }
    }

    /**
     * Task 5.8: is this {@code (user, platform)} still inside a rate-limit
     * cooldown window? Expired entries are removed on inspection so the map does
     * not grow unbounded and a later poll is allowed through.
     */
    private boolean isInCooldown(Long userId, Platform platform) {
        CooldownKey key = new CooldownKey(userId, platform);
        Instant until = cooldownUntil.get(key);
        if (until == null) {
            return false;
        }
        if (clock.instant().isBefore(until)) {
            return true;
        }
        // Cooldown expired — clear it (guarding against a concurrent refresh).
        cooldownUntil.remove(key, until);
        return false;
    }

    /**
     * Task 5.7 (Requirement 9.2): mark a successful poll of {@code platform} by
     * stamping {@code last_success_at = now}. poll_status is keyed by platform
     * (one row per platform), so this reflects the most recent successful poll of
     * the platform across all users. Upserts the row if absent.
     */
    private void recordPollSuccess(Platform platform) {
        PollStatus status = loadOrCreatePollStatus(platform);
        status.setLastSuccessAt(clock.instant());
        pollStatusRepository.save(status);
    }

    /**
     * Task 5.7 (Requirement 9.2): mark a failed poll of {@code platform} by
     * stamping {@code last_failure_at = now} and recording the failure reason.
     * Upserts the row if absent.
     */
    private void recordPollFailure(Platform platform, String reason) {
        PollStatus status = loadOrCreatePollStatus(platform);
        status.setLastFailureAt(clock.instant());
        status.setLastFailureReason(reason);
        pollStatusRepository.save(status);
    }

    /** Loads the poll_status row for {@code platform}, creating a fresh one if none exists. */
    private PollStatus loadOrCreatePollStatus(Platform platform) {
        return pollStatusRepository.findById(platform).orElseGet(() -> {
            PollStatus status = new PollStatus();
            status.setPlatform(platform);
            return status;
        });
    }

    /** Builds a compact "Type: message" failure reason for poll_status.last_failure_reason. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Persists one fetched poll batch for a (user, platform): the tasks 5.2&ndash;5.5
     * pipeline in one atomic unit.
     *
     * <p>Runs {@link #persistNewSubmissions} (5.2&ndash;5.4) to dedupe and insert new
     * rows with their computed flags, then {@link #updateDailyCounts} (5.5) to roll
     * the newly counted rows into {@code daily_counts}.
     *
     * <p>Annotated {@code @Transactional} so the submission inserts and the
     * {@code daily_counts} upsert for one batch commit atomically — a mid-write
     * failure must never leave the rollup out of sync with the rows it counts.
     * (As with {@link BackfillService#persistBackfill}, the transactional proxy
     * only applies to external callers; {@link #pollUserPlatform} invokes it on the
     * same bean, so the annotation documents the intended boundary and takes effect
     * for direct callers such as tests.)
     *
     * <p><b>Task 9.2 note:</b> the WebSocket deltas are only <em>built</em> here
     * (from the post-upsert daily counts) and returned; they are deliberately
     * <em>not</em> published inside this transactional boundary. The caller
     * ({@link #pollUserPlatform}) publishes them after this method returns so a
     * broker failure can never roll back the committed submission/daily_counts
     * writes.
     *
     * @param user           the onboarded user being polled
     * @param platform       the platform these submissions came from
     * @param rawSubmissions the fetched recent window (never null)
     * @return the leaderboard deltas for the newly counted rows, to publish after
     *         persistence commits (never null; empty when nothing was counted)
     */
    @Transactional
    List<LeaderboardUpdate> processFetched(User user, Platform platform, List<RawSubmission> rawSubmissions) {
        List<Submission> newlyCounted = persistNewSubmissions(user, platform, rawSubmissions);
        return updateDailyCounts(user, newlyCounted);
    }

    /**
     * Tasks 5.2&ndash;5.4: persist genuinely new submissions from one poll batch.
     *
     * <p><b>5.2 — dedupe + insert:</b> a raw submission is inserted only when its
     * natural key {@code (user_id, platform, problem_id, solved_at_utc)} does not
     * already exist (checked via
     * {@link SubmissionRepository#existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc}).
     * Already-present rows are skipped (Requirements 2.2, 2.3).
     *
     * <p><b>5.3 — is_first_attempt:</b> a new row is the first attempt only if the
     * user has never solved this {@code (user_id, platform, problem_id)} before.
     * "Before" spans two sources, exactly as {@link BackfillService#persistBackfill}
     * does it:
     * <ul>
     *   <li>rows already persisted in the database
     *       ({@link SubmissionRepository#existsByUserIdAndPlatformAndProblemId},
     *       ignoring timestamp), and</li>
     *   <li>earlier rows within <em>this same</em> poll batch — the batch is sorted
     *       by {@code solvedAt} ascending and a {@code seenProblemIds} set tracks
     *       problems already granted first-attempt in this run, so two new solves
     *       of the same problem in one poll don't both get
     *       {@code is_first_attempt = true} (earliest wins). Rows skipped as
     *       duplicates still mark the problem seen, so a same-batch re-solve is not
     *       mistaken for a first attempt.</li>
     * </ul>
     *
     * <p><b>5.4 — counted_for_target:</b> {@code counted_for_target =
     * is_first_attempt && user.isOnboardingComplete()}. The poll only visits
     * onboarded users, so this reduces to {@code is_first_attempt} in practice, but
     * the onboarding gate is applied explicitly to preserve design.md Property 4
     * ("counted implies first-attempt AND onboarding_complete"). Difficulty and
     * tags are mapped straight from the {@link RawSubmission}; {@code created_at}
     * is set to {@link Instant#now()} (Requirement 3.5).
     *
     * @return the newly inserted rows with {@code counted_for_target = true}, in
     *         insertion order — the input to the {@code daily_counts} upsert (5.5).
     */
    private List<Submission> persistNewSubmissions(User user, Platform platform,
                                                   List<RawSubmission> rawSubmissions) {
        if (rawSubmissions.isEmpty()) {
            return List.of();
        }

        Long userId = user.getId();

        // Assign first-attempt to the EARLIEST solve of each problem: sort ascending.
        List<RawSubmission> ordered = new ArrayList<>(rawSubmissions);
        ordered.sort(Comparator.comparing(RawSubmission::solvedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        // Problems already granted first-attempt (or seen as duplicates) within
        // THIS batch, so a second solve of the same problem is is_first_attempt=false.
        Set<String> seenProblemIds = new HashSet<>();

        List<Submission> toInsert = new ArrayList<>();
        List<Submission> counted = new ArrayList<>();
        int skipped = 0;

        for (RawSubmission raw : ordered) {
            String problemId = raw.problemId();

            // Task 5.2: idempotent dedupe on the natural key; skip already-present rows.
            if (submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                    userId, platform, problemId, raw.solvedAt())) {
                skipped++;
                // Record as seen so a later in-batch solve of the same problem is
                // not mistakenly treated as a first attempt.
                seenProblemIds.add(problemId);
                continue;
            }

            // Task 5.3: first attempt iff never solved before, in the DB or earlier
            // in this batch.
            boolean firstAttempt =
                    seenProblemIds.add(problemId)
                    && !submissionRepository.existsByUserIdAndPlatformAndProblemId(userId, platform, problemId);

            // Task 5.4: counted only when it's a first attempt AND the user is
            // onboarded (explicit gate preserves design.md Property 4).
            boolean countedForTarget = firstAttempt && user.isOnboardingComplete();

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

        // Batch-insert the genuinely new rows in one call (mirrors
        // BackfillService.persistBackfill); skipped when nothing new arrived.
        if (!toInsert.isEmpty()) {
            submissionRepository.saveAll(toInsert);
        }

        log.info("poll persist: user id={}, platform={}, inserted {} row(s) ({} counted), skipped {} duplicate(s)",
                userId, platform, toInsert.size(), counted.size(), skipped);

        return counted;
    }

    /**
     * Task 5.5: upsert {@code daily_counts} for the newly counted submissions.
     *
     * <p>Each counted submission's counting day is {@code date_ist =
     * TimeUtil.toIstDate(solved_at_utc)} — the single source of truth for IST
     * bucketing (design.md Property 5; no ad-hoc timezone math). Counted rows are
     * grouped by {@code date_ist} first so that N new solves landing on the same
     * IST day become a single upsert with a delta of N, rather than N separate
     * read-modify-write round trips (Requirements 3.6, 4.5).
     *
     * <p>For each day: load the existing {@link DailyCount} via
     * {@link DailyCountRepository#findByIdUserIdAndIdDateIst}, create it with
     * {@code count = 0} if absent, add the delta, and save.
     *
     * <p><b>target_hit (task 7.1):</b> after each upsert the day's
     * {@code target_hit} is recomputed via
     * {@link StreakService#isTargetHit(int, int)} — the single authoritative
     * definition of the rule {@code count >= daily_target} (Requirement 5.1).
     * Because this runs on every count change, the {@code daily_counts.target_hit}
     * column is always consistent with the day's fresh count.
     *
     * <p><b>Task 9.2:</b> after the upserts, this builds one
     * {@link LeaderboardUpdate} per counted submission, stamping each with the
     * post-upsert daily count for that submission's IST day (so every delta
     * reflects the day's true current total, even when several new solves land on
     * the same day). The events are returned for the caller to publish once the
     * transaction commits.
     *
     * @return the leaderboard deltas for {@code newlyCounted}, or an empty list
     *         when there is nothing to count
     */
    private List<LeaderboardUpdate> updateDailyCounts(User user, List<Submission> newlyCounted) {
        if (newlyCounted.isEmpty()) {
            return List.of();
        }

        Long userId = user.getId();

        // Group counted rows by IST day -> increment. LinkedHashMap keeps the
        // earliest-day-first order for deterministic, readable logging.
        Map<LocalDate, Integer> deltaByDay = new LinkedHashMap<>();
        for (Submission submission : newlyCounted) {
            LocalDate dateIst = TimeUtil.toIstDate(submission.getSolvedAtUtc());
            deltaByDay.merge(dateIst, 1, Integer::sum);
        }

        // Post-upsert count per IST day, used to stamp each event's newDailyCount.
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
            // Task 7.1: recompute target_hit through the single authoritative rule
            // every time the count changes (Requirement 5.1).
            daily.setTargetHit(streakService.isTargetHit(newCount, user.getDailyTarget()));

            dailyCountRepository.save(daily);
            finalCountByDay.put(dateIst, newCount);

            log.info("daily_counts upsert: user id={}, date_ist={}, +{} -> count={}, target_hit={}",
                    userId, dateIst, delta, newCount, daily.isTargetHit());
        }

        // Task 9.2: one delta per counted submission, carrying the accurate
        // post-upsert daily count for its IST day.
        List<LeaderboardUpdate> updates = new ArrayList<>(newlyCounted.size());
        for (Submission submission : newlyCounted) {
            LocalDate dateIst = TimeUtil.toIstDate(submission.getSolvedAtUtc());
            int newDailyCount = finalCountByDay.getOrDefault(dateIst, 0);
            updates.add(LeaderboardUpdate.from(user, submission, newDailyCount));
        }
        return updates;
    }

    /**
     * Task 9.2 (Requirement 7.1): push each leaderboard delta to every group the
     * user belongs to, on the channel {@code /topic/group/{groupId}}.
     *
     * <p>Resolves the user's groups via
     * {@link GroupMemberRepository#findByIdUserId(Long)} and sends each update to
     * all of them. A user in no groups publishes nothing.
     *
     * <p><b>Resilience:</b> the whole operation is wrapped in a catch-all so a
     * messaging/broker failure is logged and swallowed — it must never abort the
     * poll pipeline. This method is invoked only <em>after</em> the transactional
     * persistence in {@link #processFetched} has returned, so nothing here can
     * roll back a committed submission or daily_counts write.
     *
     * @param user    the user whose new counted solves are being announced
     * @param updates the deltas to publish (may be empty)
     */
    private void publishLeaderboardUpdates(User user, List<LeaderboardUpdate> updates) {
        if (updates.isEmpty()) {
            return;
        }
        try {
            List<GroupMember> memberships = groupMemberRepository.findByIdUserId(user.getId());
            if (memberships.isEmpty()) {
                // User is in no groups — no channel to push to (nothing to do).
                return;
            }
            for (LeaderboardUpdate update : updates) {
                for (GroupMember membership : memberships) {
                    Long groupId = membership.getId().getGroupId();
                    String destination = "/topic/group/" + groupId;
                    messagingTemplate.convertAndSend(destination, update);
                    log.info("Published leaderboard update to {} for user id={} (problem='{}', newDailyCount={})",
                            destination, user.getId(), update.problemName(), update.newDailyCount());
                }
            }
        } catch (RuntimeException e) {
            // A broker/serialization failure must not break the poll pipeline.
            log.warn("Failed to publish leaderboard update(s) for user id={} at {}: {}",
                    user.getId(), clock.instant(), e.getMessage(), e);
        }
    }
}
