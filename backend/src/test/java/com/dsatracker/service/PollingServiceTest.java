package com.dsatracker.service;

import com.dsatracker.adapter.RawSubmission;
import com.dsatracker.adapter.SubmissionFetcher;
import com.dsatracker.adapter.exception.RateLimitException;
import com.dsatracker.adapter.exception.ScrapeException;
import com.dsatracker.dto.LeaderboardUpdate;
import com.dsatracker.model.DailyCount;
import com.dsatracker.model.DailyCountId;
import com.dsatracker.model.GroupMember;
import com.dsatracker.model.GroupMemberId;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PollingService}.
 *
 * <p>Database-free: the repositories are Mockito mocks and the adapters are
 * lightweight fakes. Concerns covered:
 * <ul>
 *   <li><b>Task 5.1 skeleton</b> — {@link PollingService#pollAllUsers()} iterates
 *       onboarded users and invokes the per-platform seam only for linked
 *       platforms, routing to the correct adapter.</li>
 *   <li><b>Tasks 5.2–5.5 pipeline</b> — driven through
 *       {@link PollingService#pollUserPlatform}: dedupe by natural key, compute
 *       {@code is_first_attempt} and {@code counted_for_target}, and upsert
 *       {@code daily_counts} only for counted submissions.</li>
 *   <li><b>Task 5.6 isolation</b> — one (user, platform) throwing does not abort
 *       the polling cycle for other users/platforms.</li>
 *   <li><b>Task 5.7 poll_status</b> — success stamps {@code last_success_at};
 *       failure stamps {@code last_failure_at} + reason.</li>
 *   <li><b>Task 5.8 cooldown</b> — a rate-limit response backs the (user,
 *       platform) off for the cooldown window, then polling resumes once it
 *       expires. Driven with an adjustable {@link Clock} so no real time passes.</li>
 * </ul>
 *
 * <p>Persistence is captured through an {@code Answer} bound to both
 * {@code save(..)} and {@code saveAll(..)} so the assertions hold regardless of
 * whether the pipeline inserts rows one-by-one or in a batch — the tests pin the
 * observable behaviour (which rows, with which flags, and how many daily-count
 * upserts), not the specific repository call shape.
 */
@ExtendWith(MockitoExtension.class)
class PollingServiceTest {

    private static final Platform PLATFORM = Platform.LEETCODE;
    private static final Duration COOLDOWN = Duration.ofMinutes(15);

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private DailyCountRepository dailyCountRepository;

    @Mock
    private PollStatusRepository pollStatusRepository;

    @Mock
    private GroupMemberRepository groupMemberRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    /** Adjustable clock so the task 5.8 cooldown window can be exercised without sleeping. */
    private final MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));

    /** Rows the service persisted, captured from save(..) and/or saveAll(..). */
    private final List<Submission> savedSubmissions = new ArrayList<>();

    /**
     * A hand-rolled mutable {@link Clock}: tests read the current instant and can
     * advance it forward to simulate the passage of time deterministically.
     */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration amount) {
            this.instant = this.instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /**
     * A fake fetcher bound to one platform. Counts calls, records the last
     * username, and returns a scripted batch (defaults to empty).
     */
    private static final class RecordingFetcher implements SubmissionFetcher {
        private final Platform platform;
        private final List<RawSubmission> result;
        final AtomicInteger calls = new AtomicInteger();
        volatile String lastUsername;

        RecordingFetcher(Platform platform) {
            this(platform, List.of());
        }

        RecordingFetcher(Platform platform, List<RawSubmission> result) {
            this.platform = platform;
            this.result = result;
        }

        @Override
        public List<RawSubmission> fetchRecent(String username) {
            calls.incrementAndGet();
            lastUsername = username;
            return result;
        }

        @Override
        public Platform platform() {
            return platform;
        }
    }

    /** A fetcher that always throws the supplied error (tasks 5.6 / 5.7 failure paths). */
    private static final class FailingFetcher implements SubmissionFetcher {
        private final Platform platform;
        private final RuntimeException error;
        final AtomicInteger calls = new AtomicInteger();

        FailingFetcher(Platform platform, RuntimeException error) {
            this.platform = platform;
            this.error = error;
        }

        @Override
        public List<RawSubmission> fetchRecent(String username) {
            calls.incrementAndGet();
            throw error;
        }

        @Override
        public Platform platform() {
            return platform;
        }
    }

    /**
     * A fetcher that throws {@link RateLimitException} on its FIRST call and then
     * succeeds (empty batch) on every later call — used to prove the task 5.8
     * cooldown skips the adapter while active and resumes once it expires.
     */
    private static final class RateLimitThenOkFetcher implements SubmissionFetcher {
        private final Platform platform;
        final AtomicInteger calls = new AtomicInteger();

        RateLimitThenOkFetcher(Platform platform) {
            this.platform = platform;
        }

        @Override
        public List<RawSubmission> fetchRecent(String username) {
            if (calls.incrementAndGet() == 1) {
                throw new RateLimitException("429 Too Many Requests");
            }
            return List.of();
        }

        @Override
        public Platform platform() {
            return platform;
        }
    }

    private PollingService newService(SubmissionFetcher... fetchers) {
        return new PollingService(userRepository, submissionRepository, dailyCountRepository,
                pollStatusRepository, groupMemberRepository, new StreakService(dailyCountRepository),
                messagingTemplate, List.of(fetchers), clock, COOLDOWN);
    }

    /** A single-membership helper: user {@code userId} belongs to group {@code groupId}. */
    private GroupMember membership(long groupId, long userId) {
        return new GroupMember(new GroupMemberId(groupId, userId), Instant.parse("2024-01-01T00:00:00Z"));
    }

    /**
     * Records rows written via either {@code save} or {@code saveAll} into
     * {@link #savedSubmissions}. Stubbed leniently so tests that never insert
     * (e.g. the duplicate-skip case) don't trip Mockito's strict-stub check.
     */
    @SuppressWarnings("unchecked")
    private void recordInserts() {
        lenient().when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            savedSubmissions.add(s);
            return s;
        });
        lenient().when(submissionRepository.saveAll(any())).thenAnswer(inv -> {
            List<Submission> batch = new ArrayList<>((List<Submission>) inv.getArgument(0));
            savedSubmissions.addAll(batch);
            return batch;
        });
    }

    private User onboardedUser(long id, String lc, String cf, String gfg) {
        User user = new User();
        user.setId(id);
        user.setName("user-" + id);
        user.setOnboardingComplete(true);
        user.setCreatedAt(Instant.EPOCH);
        user.setLeetcodeUsername(lc);
        user.setCodeforcesUsername(cf);
        user.setGfgUsername(gfg);
        return user;
    }

    private User onboardedUser(long id) {
        User user = new User();
        user.setId(id);
        user.setName("user-" + id);
        user.setOnboardingComplete(true);
        user.setCreatedAt(Instant.EPOCH);
        return user;
    }

    // ------------------------------------------------------------------
    // Task 5.1: scheduling / iteration skeleton.
    // ------------------------------------------------------------------

    @Test
    void pollAllUsersFetchesEveryLinkedPlatformForEveryOnboardedUser() {
        RecordingFetcher leet = new RecordingFetcher(Platform.LEETCODE);
        RecordingFetcher cf = new RecordingFetcher(Platform.CODEFORCES);
        RecordingFetcher gfg = new RecordingFetcher(Platform.GFG);

        User u1 = onboardedUser(1L, "lc-1", "cf-1", "gfg-1"); // all three linked
        User u2 = onboardedUser(2L, "lc-2", null, "  ");      // only LeetCode linked

        when(userRepository.findAllByOnboardingCompleteTrue()).thenReturn(List.of(u1, u2));

        PollingService service = newService(leet, cf, gfg);
        service.pollAllUsers();

        verify(userRepository).findAllByOnboardingCompleteTrue();
        // u1 -> LeetCode + Codeforces + GFG ; u2 -> LeetCode only
        assertThat(leet.calls.get()).isEqualTo(2);
        assertThat(cf.calls.get()).isEqualTo(1);
        assertThat(gfg.calls.get()).isEqualTo(1);
        // Blank / null usernames were never polled.
        assertThat(cf.lastUsername).isEqualTo("cf-1");
        assertThat(gfg.lastUsername).isEqualTo("gfg-1");
    }

    @Test
    void pollAllUsersDoesNothingWhenNoOnboardedUsers() {
        RecordingFetcher leet = new RecordingFetcher(Platform.LEETCODE);
        when(userRepository.findAllByOnboardingCompleteTrue()).thenReturn(List.of());

        PollingService service = newService(leet);
        service.pollAllUsers();

        assertThat(leet.calls.get()).isZero();
    }

    @Test
    void pollUserPlatformRoutesToTheMatchingAdapterWithTheGivenUsername() {
        RecordingFetcher leet = new RecordingFetcher(Platform.LEETCODE);
        RecordingFetcher cf = new RecordingFetcher(Platform.CODEFORCES);

        PollingService service = newService(leet, cf);

        service.pollUserPlatform(onboardedUser(9L, "lc-9", null, null), Platform.LEETCODE, "lc-9");

        assertThat(leet.calls.get()).isEqualTo(1);
        assertThat(leet.lastUsername).isEqualTo("lc-9");
        assertThat(cf.calls.get()).isZero();
    }

    @Test
    void constructorRejectsDuplicateAdaptersForSamePlatform() {
        RecordingFetcher leet1 = new RecordingFetcher(Platform.LEETCODE);
        RecordingFetcher leet2 = new RecordingFetcher(Platform.LEETCODE);

        try {
            newService(leet1, leet2);
            assertThat(false).as("expected IllegalStateException for duplicate platform").isTrue();
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("LEETCODE");
        }
    }

    // ------------------------------------------------------------------
    // Tasks 5.2–5.5: the submission pipeline (driven via pollUserPlatform).
    // ------------------------------------------------------------------

    /** (a) A brand-new first-ever solve counts and rolls up into daily_counts. */
    @Test
    void newFirstEverSolveIsCountedAndDailyCountCreated() {
        recordInserts();
        User user = onboardedUser(1L);
        user.setDailyTarget(1); // one solve is enough to hit the target

        Instant solvedAt = Instant.parse("2024-01-01T10:00:00Z");
        LocalDate dateIst = TimeUtil.toIstDate(solvedAt);

        RecordingFetcher leet = new RecordingFetcher(PLATFORM,
                List.of(RawSubmission.of("two-sum", "Two Sum", solvedAt)));
        PollingService service = newService(leet);

        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                any(), any(), any(), any())).thenReturn(false);
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(any(), any(), any()))
                .thenReturn(false);
        when(dailyCountRepository.findByIdUserIdAndIdDateIst(eq(1L), eq(dateIst)))
                .thenReturn(Optional.empty());

        service.pollUserPlatform(user, PLATFORM, "lc-1");

        assertThat(savedSubmissions).hasSize(1);
        assertThat(savedSubmissions.get(0).isFirstAttempt()).isTrue();
        assertThat(savedSubmissions.get(0).isCountedForTarget()).isTrue();

        ArgumentCaptor<DailyCount> dc = ArgumentCaptor.forClass(DailyCount.class);
        verify(dailyCountRepository).save(dc.capture());
        DailyCount daily = dc.getValue();
        assertThat(daily.getId().getUserId()).isEqualTo(1L);
        assertThat(daily.getId().getDateIst()).isEqualTo(dateIst);
        assertThat(daily.getCount()).isEqualTo(1);
        assertThat(daily.isTargetHit()).isTrue(); // count(1) >= target(1)
    }

    /** (a') An existing daily_counts row is incremented and target_hit recomputed. */
    @Test
    void newFirstEverSolveIncrementsExistingDailyCount() {
        recordInserts();
        User user = onboardedUser(2L);
        user.setDailyTarget(5);

        Instant solvedAt = Instant.parse("2024-01-01T10:00:00Z");
        LocalDate dateIst = TimeUtil.toIstDate(solvedAt);

        RecordingFetcher leet = new RecordingFetcher(PLATFORM,
                List.of(RawSubmission.of("three-sum", "3Sum", solvedAt)));
        PollingService service = newService(leet);

        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                any(), any(), any(), any())).thenReturn(false);
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(any(), any(), any()))
                .thenReturn(false);
        // Already 4 solved today; this new one makes 5 -> target hit.
        when(dailyCountRepository.findByIdUserIdAndIdDateIst(eq(2L), eq(dateIst)))
                .thenReturn(Optional.of(new DailyCount(new DailyCountId(2L, dateIst), 4, false)));

        service.pollUserPlatform(user, PLATFORM, "lc-2");

        ArgumentCaptor<DailyCount> dc = ArgumentCaptor.forClass(DailyCount.class);
        verify(dailyCountRepository).save(dc.capture());
        DailyCount daily = dc.getValue();
        assertThat(daily.getCount()).isEqualTo(5);
        assertThat(daily.isTargetHit()).isTrue();
    }

    /** (b) Re-solving a problem already in the DB doesn't count and doesn't touch daily_counts. */
    @Test
    void reSolveOfKnownProblemIsNotCountedAndDailyCountUntouched() {
        recordInserts();
        User user = onboardedUser(3L);

        Instant solvedAt = Instant.parse("2024-02-01T10:00:00Z");

        RecordingFetcher leet = new RecordingFetcher(PLATFORM,
                List.of(RawSubmission.of("two-sum", "Two Sum", solvedAt)));
        PollingService service = newService(leet);

        // Natural key is new (different timestamp) so it inserts...
        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                any(), any(), any(), any())).thenReturn(false);
        // ...but the user has solved this (platform, problemId) before.
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(
                eq(3L), eq(PLATFORM), eq("two-sum"))).thenReturn(true);

        service.pollUserPlatform(user, PLATFORM, "lc-3");

        assertThat(savedSubmissions).hasSize(1);
        assertThat(savedSubmissions.get(0).isFirstAttempt()).isFalse();
        assertThat(savedSubmissions.get(0).isCountedForTarget()).isFalse();
        verify(dailyCountRepository, never()).save(any());
        // Task 9.2: a non-counted re-solve produces no leaderboard delta -> no publish.
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /** (c) A duplicate natural key is skipped entirely (no insert, no daily_counts). */
    @Test
    void duplicateNaturalKeyIsSkipped() {
        recordInserts();
        User user = onboardedUser(4L);

        Instant solvedAt = Instant.parse("2024-03-01T10:00:00Z");

        RecordingFetcher leet = new RecordingFetcher(PLATFORM,
                List.of(RawSubmission.of("two-sum", "Two Sum", solvedAt)));
        PollingService service = newService(leet);

        // Natural key already stored -> skip.
        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                eq(4L), eq(PLATFORM), eq("two-sum"), eq(solvedAt))).thenReturn(true);

        service.pollUserPlatform(user, PLATFORM, "lc-4");

        // Nothing new to insert; no daily-count upsert.
        assertThat(savedSubmissions).isEmpty();
        verify(dailyCountRepository, never()).save(any());
    }

    /** (d) Two new solves of the same problem in one batch: only the earliest is a first-attempt. */
    @Test
    void twoNewSolvesOfSameProblemInOneBatchOnlyEarliestIsFirstAttempt() {
        recordInserts();
        User user = onboardedUser(5L);
        user.setDailyTarget(5);

        Instant earlier = Instant.parse("2024-04-01T08:00:00Z");
        Instant later = earlier.plus(3, ChronoUnit.HOURS);

        // Provided out of order to prove ascending sort drives first-attempt.
        RecordingFetcher leet = new RecordingFetcher(PLATFORM, List.of(
                RawSubmission.of("two-sum", "Two Sum", later),
                RawSubmission.of("two-sum", "Two Sum", earlier)));
        PollingService service = newService(leet);

        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                any(), any(), any(), any())).thenReturn(false);
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(any(), any(), any()))
                .thenReturn(false);
        lenient().when(dailyCountRepository.findByIdUserIdAndIdDateIst(any(), any()))
                .thenReturn(Optional.empty());

        service.pollUserPlatform(user, PLATFORM, "lc-5");

        assertThat(savedSubmissions).hasSize(2);

        Submission first = savedSubmissions.stream()
                .filter(s -> s.getSolvedAtUtc().equals(earlier)).findFirst().orElseThrow();
        Submission second = savedSubmissions.stream()
                .filter(s -> s.getSolvedAtUtc().equals(later)).findFirst().orElseThrow();

        assertThat(first.isFirstAttempt()).isTrue();
        assertThat(first.isCountedForTarget()).isTrue();
        assertThat(second.isFirstAttempt()).isFalse();
        assertThat(second.isCountedForTarget()).isFalse();

        // Only the counted (earliest) solve rolled up into daily_counts — one upsert.
        verify(dailyCountRepository, org.mockito.Mockito.times(1)).save(any());
    }

    // ------------------------------------------------------------------
    // Task 5.6: per-user-per-platform try/catch isolation (Req 2.4, 9.1).
    // ------------------------------------------------------------------

    /**
     * One platform throwing mid-cycle (here GFG raising a {@link ScrapeException})
     * must not stop the other platforms/users in the same cycle from being polled,
     * and no exception may escape {@link PollingService#pollAllUsers()}.
     */
    @Test
    void oneFailingPlatformDoesNotAbortTheRestOfTheCycle() {
        // GFG blows up; LeetCode and Codeforces must still be polled.
        FailingFetcher gfg = new FailingFetcher(Platform.GFG,
                new ScrapeException("markup changed"));
        RecordingFetcher leet = new RecordingFetcher(Platform.LEETCODE);
        RecordingFetcher cf = new RecordingFetcher(Platform.CODEFORCES);

        User u1 = onboardedUser(1L, "lc-1", "cf-1", "gfg-1"); // all three linked
        User u2 = onboardedUser(2L, "lc-2", "cf-2", "gfg-2"); // all three linked

        when(userRepository.findAllByOnboardingCompleteTrue()).thenReturn(List.of(u1, u2));

        PollingService service = newService(leet, cf, gfg);

        // The failure is swallowed — the whole cycle completes without throwing.
        assertThatCode(service::pollAllUsers).doesNotThrowAnyException();

        // Every user's LeetCode + Codeforces were still polled despite GFG failing.
        assertThat(leet.calls.get()).isEqualTo(2);
        assertThat(cf.calls.get()).isEqualTo(2);
        // GFG was attempted for both users (each attempt isolated).
        assertThat(gfg.calls.get()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Task 5.7: poll_status updates (Req 9.2).
    // ------------------------------------------------------------------

    /** A successful poll stamps last_success_at on the platform's poll_status row. */
    @Test
    void successfulPollStampsLastSuccessAt() {
        // Empty batch -> success path with no persistence side effects.
        RecordingFetcher leet = new RecordingFetcher(PLATFORM);
        PollingService service = newService(leet);

        when(pollStatusRepository.findById(PLATFORM)).thenReturn(Optional.empty());

        service.pollUserPlatform(onboardedUser(1L), PLATFORM, "lc-1");

        ArgumentCaptor<PollStatus> captor = ArgumentCaptor.forClass(PollStatus.class);
        verify(pollStatusRepository).save(captor.capture());
        PollStatus status = captor.getValue();
        assertThat(status.getPlatform()).isEqualTo(PLATFORM);
        assertThat(status.getLastSuccessAt()).isEqualTo(clock.instant());
        assertThat(status.getLastFailureAt()).isNull();
    }

    /** A failed poll stamps last_failure_at and a concise reason (type + message). */
    @Test
    void failedPollStampsLastFailureAtAndReason() {
        FailingFetcher leet = new FailingFetcher(PLATFORM,
                new RuntimeException("connect timed out"));
        PollingService service = newService(leet);

        when(pollStatusRepository.findById(PLATFORM)).thenReturn(Optional.empty());

        service.pollUserPlatform(onboardedUser(1L), PLATFORM, "lc-1");

        ArgumentCaptor<PollStatus> captor = ArgumentCaptor.forClass(PollStatus.class);
        verify(pollStatusRepository).save(captor.capture());
        PollStatus status = captor.getValue();
        assertThat(status.getPlatform()).isEqualTo(PLATFORM);
        assertThat(status.getLastFailureAt()).isEqualTo(clock.instant());
        assertThat(status.getLastFailureReason())
                .contains("RuntimeException")
                .contains("connect timed out");
        assertThat(status.getLastSuccessAt()).isNull();
    }

    /** A GFG parse failure records the GFG_PARSE_FAILURE marker in the failure reason. */
    @Test
    void gfgParseFailureIsRecordedAsGfgParseFailure() {
        FailingFetcher gfg = new FailingFetcher(Platform.GFG,
                new ScrapeException("GFG_PARSE_FAILURE: profile markup changed"));
        PollingService service = newService(gfg);

        when(pollStatusRepository.findById(Platform.GFG)).thenReturn(Optional.empty());

        service.pollUserPlatform(onboardedUser(1L, null, null, "gfg-1"), Platform.GFG, "gfg-1");

        ArgumentCaptor<PollStatus> captor = ArgumentCaptor.forClass(PollStatus.class);
        verify(pollStatusRepository).save(captor.capture());
        assertThat(captor.getValue().getLastFailureReason()).contains("GFG_PARSE_FAILURE");
    }

    // ------------------------------------------------------------------
    // Task 5.8: rate-limit cooldown (Req 2.5).
    // ------------------------------------------------------------------

    /**
     * After a {@link RateLimitException}, the same (user, platform) is skipped for
     * the cooldown window (adapter NOT called), then polled again once the window
     * expires. Time is advanced via the injected {@link MutableClock}.
     */
    @Test
    void rateLimitTriggersCooldownThatSkipsThenResumes() {
        RateLimitThenOkFetcher leet = new RateLimitThenOkFetcher(PLATFORM);
        PollingService service = newService(leet);

        lenient().when(pollStatusRepository.findById(PLATFORM)).thenReturn(Optional.empty());

        User user = onboardedUser(1L);

        // 1) First poll hits a rate-limit -> adapter called once, cooldown armed.
        service.pollUserPlatform(user, PLATFORM, "lc-1");
        assertThat(leet.calls.get()).isEqualTo(1);

        // 2) Still inside the 15-min window -> adapter must NOT be called again.
        clock.advance(Duration.ofMinutes(5));
        service.pollUserPlatform(user, PLATFORM, "lc-1");
        assertThat(leet.calls.get()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(9)); // 14 min total, still cooling down
        service.pollUserPlatform(user, PLATFORM, "lc-1");
        assertThat(leet.calls.get()).isEqualTo(1);

        // 3) Past the 15-min window -> cooldown expired, adapter called again.
        clock.advance(Duration.ofMinutes(2)); // 16 min total
        service.pollUserPlatform(user, PLATFORM, "lc-1");
        assertThat(leet.calls.get()).isEqualTo(2);
    }

    /** The cooldown is scoped per (user, platform): a rate-limit for one user does not skip another. */
    @Test
    void cooldownIsScopedPerUserAndPlatform() {
        RateLimitThenOkFetcher leet = new RateLimitThenOkFetcher(PLATFORM);
        PollingService service = newService(leet);

        lenient().when(pollStatusRepository.findById(PLATFORM)).thenReturn(Optional.empty());

        // User 1 gets rate-limited -> cooldown armed for (user 1, LEETCODE).
        service.pollUserPlatform(onboardedUser(1L), PLATFORM, "lc-1");
        assertThat(leet.calls.get()).isEqualTo(1);

        // User 2 (different key) is NOT in cooldown -> adapter is called.
        service.pollUserPlatform(onboardedUser(2L), PLATFORM, "lc-2");
        assertThat(leet.calls.get()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Task 9.2: publish a leaderboard update on a new counted submission
    // to /topic/group/{groupId} for every group the user belongs to (Req 7.1).
    // ------------------------------------------------------------------

    /**
     * A new counted solve is published to {@code /topic/group/{groupId}} for
     * every group the user is a member of, with the correct payload fields
     * (including the post-upsert daily count and the user's target).
     */
    @Test
    void newCountedSolveIsPublishedToEveryGroupTheUserBelongsTo() {
        recordInserts();
        User user = onboardedUser(1L);
        user.setDailyTarget(3);

        Instant solvedAt = Instant.parse("2024-01-01T10:00:00Z");
        LocalDate dateIst = TimeUtil.toIstDate(solvedAt);

        RecordingFetcher leet = new RecordingFetcher(PLATFORM,
                List.of(RawSubmission.of("two-sum", "Two Sum", solvedAt)));
        PollingService service = newService(leet);

        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                any(), any(), any(), any())).thenReturn(false);
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(any(), any(), any()))
                .thenReturn(false);
        // Existing count 1 -> this solve makes 2.
        when(dailyCountRepository.findByIdUserIdAndIdDateIst(eq(1L), eq(dateIst)))
                .thenReturn(Optional.of(new DailyCount(new DailyCountId(1L, dateIst), 1, false)));
        // User belongs to two groups.
        when(groupMemberRepository.findByIdUserId(1L))
                .thenReturn(List.of(membership(10L, 1L), membership(20L, 1L)));

        service.pollUserPlatform(user, PLATFORM, "lc-1");

        ArgumentCaptor<String> destinations = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LeaderboardUpdate> payloads = ArgumentCaptor.forClass(LeaderboardUpdate.class);
        verify(messagingTemplate, times(2)).convertAndSend(destinations.capture(), payloads.capture());

        assertThat(destinations.getAllValues())
                .containsExactlyInAnyOrder("/topic/group/10", "/topic/group/20");

        LeaderboardUpdate update = payloads.getAllValues().get(0);
        assertThat(update.userId()).isEqualTo(1L);
        assertThat(update.userName()).isEqualTo("user-1");
        assertThat(update.problemName()).isEqualTo("Two Sum");
        assertThat(update.platform()).isEqualTo("LEETCODE");
        assertThat(update.newDailyCount()).isEqualTo(2); // 1 existing + this solve
        assertThat(update.target()).isEqualTo(3);
    }

    /** A user who belongs to no groups triggers no publish at all. */
    @Test
    void newCountedSolveForUserInNoGroupsPublishesNothing() {
        recordInserts();
        User user = onboardedUser(2L);

        Instant solvedAt = Instant.parse("2024-01-01T10:00:00Z");
        LocalDate dateIst = TimeUtil.toIstDate(solvedAt);

        RecordingFetcher leet = new RecordingFetcher(PLATFORM,
                List.of(RawSubmission.of("two-sum", "Two Sum", solvedAt)));
        PollingService service = newService(leet);

        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                any(), any(), any(), any())).thenReturn(false);
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(any(), any(), any()))
                .thenReturn(false);
        when(dailyCountRepository.findByIdUserIdAndIdDateIst(eq(2L), eq(dateIst)))
                .thenReturn(Optional.empty());
        when(groupMemberRepository.findByIdUserId(2L)).thenReturn(List.of());

        service.pollUserPlatform(user, PLATFORM, "lc-2");

        // The solve was still counted/persisted, but there is no channel to push to.
        assertThat(savedSubmissions).hasSize(1);
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * A broker failure while publishing is swallowed: the poll still completes
     * successfully and {@code poll_status} is still stamped with last_success_at.
     * A messaging failure must never abort the poll pipeline.
     */
    @Test
    void messagingFailureIsSwallowedAndPollStillSucceeds() {
        recordInserts();
        User user = onboardedUser(1L);

        Instant solvedAt = Instant.parse("2024-01-01T10:00:00Z");
        LocalDate dateIst = TimeUtil.toIstDate(solvedAt);

        RecordingFetcher leet = new RecordingFetcher(PLATFORM,
                List.of(RawSubmission.of("two-sum", "Two Sum", solvedAt)));
        PollingService service = newService(leet);

        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                any(), any(), any(), any())).thenReturn(false);
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(any(), any(), any()))
                .thenReturn(false);
        when(dailyCountRepository.findByIdUserIdAndIdDateIst(eq(1L), eq(dateIst)))
                .thenReturn(Optional.empty());
        when(groupMemberRepository.findByIdUserId(1L)).thenReturn(List.of(membership(10L, 1L)));
        when(pollStatusRepository.findById(PLATFORM)).thenReturn(Optional.empty());

        // The broker is down: every publish throws.
        doThrow(new MessagingException("broker unavailable"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        // No exception escapes the poll.
        assertThatCode(() -> service.pollUserPlatform(user, PLATFORM, "lc-1"))
                .doesNotThrowAnyException();

        // Persistence completed and poll_status recorded a SUCCESS despite the broker failure.
        assertThat(savedSubmissions).hasSize(1);
        ArgumentCaptor<PollStatus> captor = ArgumentCaptor.forClass(PollStatus.class);
        verify(pollStatusRepository).save(captor.capture());
        PollStatus status = captor.getValue();
        assertThat(status.getLastSuccessAt()).isEqualTo(clock.instant());
        assertThat(status.getLastFailureAt()).isNull();
    }
}
