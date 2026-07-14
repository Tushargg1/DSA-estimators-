package com.dsatracker.service;

import com.dsatracker.adapter.RawSubmission;
import com.dsatracker.adapter.SubmissionFetcher;
import com.dsatracker.adapter.exception.ScrapeException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * End-to-end (task 11.1&ndash;11.5) integration tests for the core counting
 * invariants. Unlike the isolated unit tests in {@link PollingServiceTest} and
 * {@link BackfillServiceTest} — which stub each repository call per test — these
 * wire the <em>real</em> {@link PollingService}, {@link BackfillService} and
 * {@link StreakService} together against <b>stateful in-memory fake
 * repositories</b>, so the full pipeline runs end-to-end:
 *
 * <pre>
 *   fetch -&gt; dedupe -&gt; is_first_attempt -&gt; counted_for_target
 *         -&gt; daily_counts upsert -&gt; target_hit -&gt; streaks
 * </pre>
 *
 * <h2>Database approach (no Docker, no Postgres)</h2>
 * This host has no Docker and the production schema uses Postgres-specific types
 * ({@code TEXT[]} arrays, {@code BIGSERIAL}) that H2 does not map cleanly, so
 * Testcontainers and embedded H2 are both avoided. Instead the JPA repository
 * interfaces are backed by <b>stateful in-memory fakes</b>: each repository is a
 * Mockito mock whose relevant methods delegate (via {@code thenAnswer}) to real
 * backing collections held on {@link InMemoryRepositories}. State therefore
 * accumulates across calls exactly like a real datasource would within a test —
 * identity-key dedupe, "solved before?" lookups, the {@code daily_counts} upsert
 * keyed by {@code (user_id, date_ist)}, and IDENTITY id assignment on insert are
 * all emulated. This keeps the tests deterministic and runnable under
 * {@code mvnw clean verify} with zero external dependencies.
 */
class CountingPipelineIntegrationTest {

    private InMemoryRepositories repos;
    private SimpMessagingTemplate messagingTemplate;
    private StreakService streakService;

    /** Fixed clock at a mid-day UTC instant so poll_status stamps are deterministic. */
    private final Clock fixedClock = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repos = new InMemoryRepositories();
        messagingTemplate = mock(SimpMessagingTemplate.class);
        streakService = new StreakService(repos.dailyCounts, fixedClock);
    }

    /** Builds a real {@link PollingService} wired to the in-memory repos and the given fetchers. */
    private PollingService pollingService(SubmissionFetcher... fetchers) {
        return new PollingService(
                repos.users, repos.submissions, repos.dailyCounts, repos.pollStatus,
                repos.groupMembers, streakService, messagingTemplate, List.of(fetchers),
                fixedClock, Duration.ofMinutes(15));
    }

    /** Builds a real {@link BackfillService} wired to the in-memory repos and the given fetchers. */
    private BackfillService backfillService(SubmissionFetcher... fetchers) {
        return new BackfillService(repos.users, repos.submissions, List.of(fetchers));
    }

    private User onboardedUser(long id, String leetcodeUsername) {
        User user = new User();
        user.setId(id);
        user.setName("user-" + id);
        user.setEmail("user" + id + "@example.com");
        user.setLeetcodeUsername(leetcodeUsername);
        user.setDailyTarget(5);
        user.setOnboardingComplete(true);
        user.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        repos.users.save(user);
        return user;
    }

    // ================================================================
    // 11.1 — first-ever solve counts and increments daily_counts.
    //        (Requirements 3.1, 3.2, 3.5)
    // ================================================================

    @Test
    @DisplayName("11.1 first-ever solve: is_first_attempt & counted_for_target true, daily_counts +1")
    void firstEverSolveIsCountedAndDailyCountIncremented() {
        User user = onboardedUser(1L, "lc-1");

        Instant solvedAt = Instant.parse("2024-06-01T10:00:00Z");
        LocalDate dateIst = TimeUtil.toIstDate(solvedAt);

        ScriptedFetcher leet = new ScriptedFetcher(Platform.LEETCODE,
                List.of(RawSubmission.of("two-sum", "Two Sum", solvedAt)));

        pollingService(leet).pollUserPlatform(user, Platform.LEETCODE, "lc-1");

        // The persisted row went the whole way through the real pipeline.
        assertThat(repos.submissionStore).hasSize(1);
        Submission persisted = repos.submissionStore.get(0);
        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getProblemId()).isEqualTo("two-sum");
        assertThat(persisted.isFirstAttempt()).isTrue();
        assertThat(persisted.isCountedForTarget()).isTrue();

        // daily_counts for that IST day incremented by exactly 1.
        DailyCount daily = repos.dailyCounts.findByIdUserIdAndIdDateIst(1L, dateIst).orElseThrow();
        assertThat(daily.getCount()).isEqualTo(1);
        assertThat(daily.getId().getDateIst()).isEqualTo(dateIst);
    }

    // ================================================================
    // 11.2 — re-solving an already-solved problem does NOT count and
    //        does NOT increment daily_counts. (Requirement 3.3)
    // ================================================================

    @Test
    @DisplayName("11.2 re-solve of a known problem: not counted, daily_counts untouched")
    void reSolveOfKnownProblemIsNotCountedAndDailyCountUntouched() {
        User user = onboardedUser(2L, "lc-2");

        // The user already solved "two-sum" earlier — persisted directly in the store,
        // together with the daily_counts row that first solve produced.
        Instant firstSolvedAt = Instant.parse("2024-05-01T10:00:00Z");
        LocalDate firstDateIst = TimeUtil.toIstDate(firstSolvedAt);
        Submission original = new Submission();
        original.setUserId(2L);
        original.setPlatform(Platform.LEETCODE);
        original.setProblemId("two-sum");
        original.setProblemName("Two Sum");
        original.setSolvedAtUtc(firstSolvedAt);
        original.setFirstAttempt(true);
        original.setCountedForTarget(true);
        original.setCreatedAt(firstSolvedAt);
        repos.submissions.save(original);
        repos.dailyCounts.save(new DailyCount(new DailyCountId(2L, firstDateIst), 1, false));

        // Now the poll sees the SAME problem solved again on a later day.
        Instant reSolvedAt = Instant.parse("2024-06-01T10:00:00Z");
        LocalDate reSolveDateIst = TimeUtil.toIstDate(reSolvedAt);
        ScriptedFetcher leet = new ScriptedFetcher(Platform.LEETCODE,
                List.of(RawSubmission.of("two-sum", "Two Sum", reSolvedAt)));

        pollingService(leet).pollUserPlatform(user, Platform.LEETCODE, "lc-2");

        // The new row was inserted (new natural key) but flagged not-first / not-counted.
        assertThat(repos.submissionStore).hasSize(2);
        Submission reSolve = repos.submissionStore.stream()
                .filter(s -> s.getSolvedAtUtc().equals(reSolvedAt)).findFirst().orElseThrow();
        assertThat(reSolve.isFirstAttempt()).isFalse();
        assertThat(reSolve.isCountedForTarget()).isFalse();

        // daily_counts DID NOT change: still one row (the original day), no new bucket.
        assertThat(repos.dailyCountStore).hasSize(1);
        assertThat(repos.dailyCounts.findByIdUserIdAndIdDateIst(2L, firstDateIst).orElseThrow().getCount())
                .isEqualTo(1);
        assertThat(repos.dailyCounts.findByIdUserIdAndIdDateIst(2L, reSolveDateIst)).isEmpty();
    }

    // ================================================================
    // 11.3 — backfill never scores: rows counted_for_target=false even
    //        when is_first_attempt=true, and daily_counts never moves.
    //        (Requirements 1.5, 3.4)
    // ================================================================

    @Test
    @DisplayName("11.3 backfill: historical rows never count, daily_counts never incremented")
    void backfillNeverIncrementsDailyCounts() {
        // A user still onboarding (backfill runs before onboarding_complete flips).
        User user = new User();
        user.setId(3L);
        user.setName("user-3");
        user.setEmail("user3@example.com");
        user.setLeetcodeUsername("lc-3");
        user.setDailyTarget(5);
        user.setOnboardingComplete(false);
        user.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        repos.users.save(user);

        Instant t0 = Instant.parse("2024-03-01T10:00:00Z");
        Instant t1 = Instant.parse("2024-03-02T10:00:00Z");
        ScriptedFetcher leet = new ScriptedFetcher(Platform.LEETCODE, List.of(
                RawSubmission.of("two-sum", "Two Sum", t0),          // first attempt
                RawSubmission.of("add-two", "Add Two Numbers", t1))); // first attempt

        backfillService(leet).runBackfill(3L);

        // Two historical rows imported; is_first_attempt computed normally (both true here)...
        assertThat(repos.submissionStore).hasSize(2);
        assertThat(repos.submissionStore).allSatisfy(s ->
                // ...but counted_for_target is ALWAYS false for backfilled rows.
                assertThat(s.isCountedForTarget()).isFalse());
        assertThat(repos.submissionStore).anySatisfy(s -> assertThat(s.isFirstAttempt()).isTrue());

        // daily_counts was NEVER touched by backfill.
        assertThat(repos.dailyCountStore).isEmpty();

        // Onboarding still completed (Requirement 1.6 side effect of runBackfill).
        assertThat(repos.users.findById(3L).orElseThrow().isOnboardingComplete()).isTrue();
    }

    // ================================================================
    // 11.4 — IST midnight boundary: 11:58 PM IST and 12:02 AM IST land
    //        in different (consecutive) daily_counts buckets.
    //        (Requirements 4.3, 4.4)
    // ================================================================

    @Test
    @DisplayName("11.4 IST midnight boundary: 11:58 PM vs 12:02 AM IST land on consecutive IST days")
    void istMidnightBoundarySplitsIntoConsecutiveDailyCountBuckets() {
        User user = onboardedUser(4L, "lc-4");

        // 11:58 PM IST on 2024-06-01  ==  18:28 UTC on 2024-06-01.
        Instant beforeMidnightUtc = Instant.parse("2024-06-01T18:28:00Z");
        // 12:02 AM IST on 2024-06-02  ==  18:32 UTC on 2024-06-01.
        Instant afterMidnightUtc = Instant.parse("2024-06-01T18:32:00Z");

        LocalDate dayBefore = TimeUtil.toIstDate(beforeMidnightUtc);
        LocalDate dayAfter = TimeUtil.toIstDate(afterMidnightUtc);

        // Sanity: the two instants really do fall on consecutive IST calendar days.
        assertThat(dayAfter).isEqualTo(dayBefore.plusDays(1));

        // Two distinct problems so both are genuine first-attempts and both count.
        ScriptedFetcher leet = new ScriptedFetcher(Platform.LEETCODE, List.of(
                RawSubmission.of("late-night", "Late Night", beforeMidnightUtc),
                RawSubmission.of("early-morn", "Early Morning", afterMidnightUtc)));

        pollingService(leet).pollUserPlatform(user, Platform.LEETCODE, "lc-4");

        assertThat(repos.submissionStore).hasSize(2);

        // Each solve landed in its own IST-day bucket, each with a count of 1.
        DailyCount before = repos.dailyCounts.findByIdUserIdAndIdDateIst(4L, dayBefore).orElseThrow();
        DailyCount after = repos.dailyCounts.findByIdUserIdAndIdDateIst(4L, dayAfter).orElseThrow();
        assertThat(before.getCount()).isEqualTo(1);
        assertThat(after.getCount()).isEqualTo(1);
        assertThat(repos.dailyCountStore).hasSize(2);
    }

    // ================================================================
    // 11.5 — network-failure isolation (Requirements 2.4, 9.1).
    //
    // The primary verification is the MANUAL procedure documented in
    //   src/test/resources/manual-tests/11.5-network-isolation.md
    // Automated coverage already exists in
    //   PollingServiceTest.oneFailingPlatformDoesNotAbortTheRestOfTheCycle.
    // The test below complements those by driving the REAL end-to-end pipeline
    // (through pollAllUsers -> pollUserPlatform -> persistence) and asserting
    // per-user-per-platform isolation: one platform failing must not stop other
    // platforms/users from being processed and persisted.
    // ================================================================

    @Test
    @DisplayName("11.5 one platform failing does not stop others processing through the real pipeline")
    void oneFailingPlatformDoesNotBlockOthersEndToEnd() {
        // Two onboarded users, each linked to LeetCode (works) and GFG (down).
        User u1 = onboardedUser(5L, "lc-5");
        u1.setGfgUsername("gfg-5");
        repos.users.save(u1);
        User u2 = onboardedUser(6L, "lc-6");
        u2.setGfgUsername("gfg-6");
        repos.users.save(u2);

        Instant solvedAt = Instant.parse("2024-06-01T09:00:00Z");
        LocalDate dateIst = TimeUtil.toIstDate(solvedAt);

        // LeetCode returns a first-ever counted solve for whichever user is polled.
        SubmissionFetcher leet = new SubmissionFetcher() {
            @Override
            public List<RawSubmission> fetchRecent(String username) {
                // Distinct problem id per user so both are genuine first-attempts.
                return List.of(RawSubmission.of("prob-" + username, "Problem " + username, solvedAt));
            }

            @Override
            public Platform platform() {
                return Platform.LEETCODE;
            }
        };
        // GFG always fails mid-poll (simulates the disconnected platform).
        SubmissionFetcher gfg = new SubmissionFetcher() {
            @Override
            public List<RawSubmission> fetchRecent(String username) {
                throw new ScrapeException("network down / markup unreachable");
            }

            @Override
            public Platform platform() {
                return Platform.GFG;
            }
        };

        pollingService(leet, gfg).pollAllUsers();

        // Despite GFG failing for BOTH users, each user's LeetCode solve was
        // persisted and counted through the real pipeline.
        assertThat(repos.submissionStore).hasSize(2);
        assertThat(repos.submissionStore).allSatisfy(s -> {
            assertThat(s.getPlatform()).isEqualTo(Platform.LEETCODE);
            assertThat(s.isCountedForTarget()).isTrue();
        });
        assertThat(repos.dailyCounts.findByIdUserIdAndIdDateIst(5L, dateIst).orElseThrow().getCount())
                .isEqualTo(1);
        assertThat(repos.dailyCounts.findByIdUserIdAndIdDateIst(6L, dateIst).orElseThrow().getCount())
                .isEqualTo(1);

        // The failing platform (GFG) recorded a failure; the working one recorded success.
        assertThat(repos.pollStatus.findById(Platform.GFG).orElseThrow().getLastFailureAt()).isNotNull();
        assertThat(repos.pollStatus.findById(Platform.LEETCODE).orElseThrow().getLastSuccessAt()).isNotNull();
    }

    // ================================================================
    // Test fixtures: scripted fetcher + stateful in-memory repositories.
    // ================================================================

    /** A fetcher bound to one platform that returns a scripted, fixed batch. */
    private static final class ScriptedFetcher implements SubmissionFetcher {
        private final Platform platform;
        private final List<RawSubmission> batch;

        ScriptedFetcher(Platform platform, List<RawSubmission> batch) {
            this.platform = platform;
            this.batch = batch;
        }

        @Override
        public List<RawSubmission> fetchRecent(String username) {
            return batch;
        }

        @Override
        public Platform platform() {
            return platform;
        }
    }

    /**
     * Holds the stateful backing collections and the Mockito-mocked repository
     * interfaces whose methods delegate to those collections. The services under
     * test see ordinary repository beans; the test reads the collections directly
     * ({@link #submissionStore}, {@link #dailyCountStore}) to make assertions.
     */
    @SuppressWarnings("unchecked")
    private static final class InMemoryRepositories {
        final List<Submission> submissionStore = new ArrayList<>();
        final Map<DailyCountId, DailyCount> dailyCountStore = new LinkedHashMap<>();
        final Map<Long, User> userStore = new LinkedHashMap<>();
        final Map<Platform, PollStatus> pollStatusStore = new EnumMap<>(Platform.class);
        private final AtomicLong submissionSeq = new AtomicLong();

        final SubmissionRepository submissions = mock(SubmissionRepository.class);
        final DailyCountRepository dailyCounts = mock(DailyCountRepository.class);
        final UserRepository users = mock(UserRepository.class);
        final PollStatusRepository pollStatus = mock(PollStatusRepository.class);
        final GroupMemberRepository groupMembers = mock(GroupMemberRepository.class);

        InMemoryRepositories() {
            wireSubmissions();
            wireDailyCounts();
            wireUsers();
            wirePollStatus();
            // No memberships -> the pipeline's leaderboard publish is a no-op.
            Mockito.when(groupMembers.findByIdUserId(ArgumentMatchers.anyLong()))
                    .thenReturn(List.<GroupMember>of());
        }

        private void wireSubmissions() {
            Mockito.when(submissions.save(ArgumentMatchers.any(Submission.class))).thenAnswer(inv -> {
                Submission s = inv.getArgument(0);
                insertSubmission(s);
                return s;
            });
            Mockito.when(submissions.saveAll(ArgumentMatchers.any())).thenAnswer(inv -> {
                List<Submission> batch = new ArrayList<>((List<Submission>) inv.getArgument(0));
                batch.forEach(this::insertSubmission);
                return batch;
            });
            Mockito.when(submissions.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                    ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any())).thenAnswer(inv -> {
                Long userId = inv.getArgument(0);
                Platform platform = inv.getArgument(1);
                String problemId = inv.getArgument(2);
                Instant solvedAt = inv.getArgument(3);
                return submissionStore.stream().anyMatch(s ->
                        Objects.equals(s.getUserId(), userId)
                                && s.getPlatform() == platform
                                && Objects.equals(s.getProblemId(), problemId)
                                && Objects.equals(s.getSolvedAtUtc(), solvedAt));
            });
            Mockito.when(submissions.existsByUserIdAndPlatformAndProblemId(
                    ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenAnswer(inv -> {
                        Long userId = inv.getArgument(0);
                        Platform platform = inv.getArgument(1);
                        String problemId = inv.getArgument(2);
                        return submissionStore.stream().anyMatch(s ->
                                Objects.equals(s.getUserId(), userId)
                                        && s.getPlatform() == platform
                                        && Objects.equals(s.getProblemId(), problemId));
                    });
        }

        private void insertSubmission(Submission s) {
            if (s.getId() == null) {
                s.setId(submissionSeq.incrementAndGet());
            }
            submissionStore.add(s);
        }

        private void wireDailyCounts() {
            Mockito.when(dailyCounts.save(ArgumentMatchers.any(DailyCount.class))).thenAnswer(inv -> {
                DailyCount dc = inv.getArgument(0);
                dailyCountStore.put(dc.getId(), dc);
                return dc;
            });
            Mockito.when(dailyCounts.findByIdUserIdAndIdDateIst(
                    ArgumentMatchers.anyLong(), ArgumentMatchers.any(LocalDate.class)))
                    .thenAnswer(inv -> {
                        Long userId = inv.getArgument(0);
                        LocalDate date = inv.getArgument(1);
                        return Optional.ofNullable(dailyCountStore.get(new DailyCountId(userId, date)));
                    });
            Mockito.when(dailyCounts.findByIdUserIdOrderByIdDateIstDesc(ArgumentMatchers.anyLong()))
                    .thenAnswer(inv -> {
                        Long userId = inv.getArgument(0);
                        return dailyCountStore.values().stream()
                                .filter(dc -> Objects.equals(dc.getId().getUserId(), userId))
                                .sorted(Comparator.comparing((DailyCount dc) -> dc.getId().getDateIst()).reversed())
                                .collect(Collectors.toList());
                    });
        }

        private void wireUsers() {
            Mockito.when(users.save(ArgumentMatchers.any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                userStore.put(u.getId(), u);
                return u;
            });
            Mockito.when(users.findById(ArgumentMatchers.anyLong())).thenAnswer(inv ->
                    Optional.ofNullable(userStore.get(inv.<Long>getArgument(0))));
            Mockito.when(users.findAllByOnboardingCompleteTrue()).thenAnswer(inv ->
                    userStore.values().stream()
                            .filter(User::isOnboardingComplete)
                            .collect(Collectors.toList()));
        }

        private void wirePollStatus() {
            Mockito.when(pollStatus.save(ArgumentMatchers.any(PollStatus.class))).thenAnswer(inv -> {
                PollStatus ps = inv.getArgument(0);
                pollStatusStore.put(ps.getPlatform(), ps);
                return ps;
            });
            Mockito.when(pollStatus.findById(ArgumentMatchers.any(Platform.class))).thenAnswer(inv ->
                    Optional.ofNullable(pollStatusStore.get(inv.<Platform>getArgument(0))));
        }
    }
}
