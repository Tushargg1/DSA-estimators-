package com.dsatracker.service;

import com.dsatracker.adapter.RawSubmission;
import com.dsatracker.adapter.SubmissionFetcher;
import com.dsatracker.adapter.exception.ScrapeException;
import com.dsatracker.model.Platform;
import com.dsatracker.model.Submission;
import com.dsatracker.model.User;
import com.dsatracker.repository.SubmissionRepository;
import com.dsatracker.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BackfillService#persistBackfill} (task 4.2).
 *
 * <p>These are database-free: {@link SubmissionRepository} is a Mockito mock, so
 * the tests assert on what the service <em>would</em> persist rather than on a
 * live datasource. They pin the three guarantees of the backfill persistence
 * seam:
 * <ul>
 *   <li>every backfilled row has {@code counted_for_target = false}
 *       (Requirements 1.5 / 3.4, design.md Property 3);</li>
 *   <li>{@code is_first_attempt} is true only for the earliest solve of a given
 *       problem, both against DB history and within the same batch;</li>
 *   <li>rows whose natural key already exists are skipped (idempotent re-run).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BackfillServiceTest {

    private static final Platform PLATFORM = Platform.LEETCODE;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubmissionRepository submissionRepository;

    private BackfillService newService() {
        // No fetchers needed for persistBackfill tests.
        return new BackfillService(userRepository, submissionRepository, List.<SubmissionFetcher>of());
    }

    private User userWithId(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    @SuppressWarnings("unchecked")
    private List<Submission> capturedSaveAll() {
        ArgumentCaptor<List<Submission>> captor = ArgumentCaptor.forClass(List.class);
        verify(submissionRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void allBackfilledRowsHaveCountedForTargetFalse() {
        BackfillService service = newService();
        User user = userWithId(1L);

        Instant t0 = Instant.parse("2024-01-01T10:00:00Z");
        List<RawSubmission> raw = List.of(
                RawSubmission.of("two-sum", "Two Sum", t0),
                RawSubmission.of("add-two-numbers", "Add Two Numbers", t0.plus(1, ChronoUnit.HOURS))
        );

        // Nothing pre-exists in the DB.
        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                any(), any(), any(), any())).thenReturn(false);
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(any(), any(), any()))
                .thenReturn(false);

        service.persistBackfill(user, PLATFORM, raw);

        List<Submission> saved = capturedSaveAll();
        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(s -> {
            assertThat(s.isCountedForTarget()).isFalse();
            assertThat(s.getUserId()).isEqualTo(1L);
            assertThat(s.getPlatform()).isEqualTo(PLATFORM);
            assertThat(s.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void firstAttemptIsTrueOnlyForEarliestOccurrenceWithinBatch() {
        BackfillService service = newService();
        User user = userWithId(2L);

        Instant earlier = Instant.parse("2024-01-01T08:00:00Z");
        Instant later = Instant.parse("2024-03-01T08:00:00Z");

        // Same problem solved twice; provided out of chronological order to prove
        // the service sorts ascending before assigning first-attempt.
        List<RawSubmission> raw = List.of(
                RawSubmission.of("two-sum", "Two Sum", later),
                RawSubmission.of("two-sum", "Two Sum", earlier)
        );

        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                any(), any(), any(), any())).thenReturn(false);
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(any(), any(), any()))
                .thenReturn(false);

        service.persistBackfill(user, PLATFORM, raw);

        List<Submission> saved = capturedSaveAll();
        assertThat(saved).hasSize(2);

        Submission first = saved.stream()
                .filter(s -> s.getSolvedAtUtc().equals(earlier)).findFirst().orElseThrow();
        Submission second = saved.stream()
                .filter(s -> s.getSolvedAtUtc().equals(later)).findFirst().orElseThrow();

        assertThat(first.isFirstAttempt()).isTrue();
        assertThat(second.isFirstAttempt()).isFalse();
        // Invariant holds regardless of first-attempt status.
        assertThat(first.isCountedForTarget()).isFalse();
        assertThat(second.isCountedForTarget()).isFalse();
    }

    @Test
    void firstAttemptIsFalseWhenProblemAlreadySolvedInDb() {
        BackfillService service = newService();
        User user = userWithId(3L);

        Instant t0 = Instant.parse("2024-01-01T10:00:00Z");
        List<RawSubmission> raw = List.of(RawSubmission.of("two-sum", "Two Sum", t0));

        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                any(), any(), any(), any())).thenReturn(false);
        // User has solved this problem before (different timestamp already in DB).
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(
                eq(3L), eq(PLATFORM), eq("two-sum"))).thenReturn(true);

        service.persistBackfill(user, PLATFORM, raw);

        List<Submission> saved = capturedSaveAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).isFirstAttempt()).isFalse();
        assertThat(saved.get(0).isCountedForTarget()).isFalse();
    }

    @Test
    void existingNaturalKeysAreSkipped() {
        BackfillService service = newService();
        User user = userWithId(4L);

        Instant t0 = Instant.parse("2024-01-01T10:00:00Z");
        Instant t1 = t0.plus(2, ChronoUnit.HOURS);
        List<RawSubmission> raw = List.of(
                RawSubmission.of("two-sum", "Two Sum", t0),          // already in DB -> skip
                RawSubmission.of("add-two-numbers", "Add Two Numbers", t1) // new -> insert
        );

        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                eq(4L), eq(PLATFORM), eq("two-sum"), eq(t0))).thenReturn(true);
        when(submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                eq(4L), eq(PLATFORM), eq("add-two-numbers"), eq(t1))).thenReturn(false);
        when(submissionRepository.existsByUserIdAndPlatformAndProblemId(
                eq(4L), eq(PLATFORM), eq("add-two-numbers"))).thenReturn(false);

        service.persistBackfill(user, PLATFORM, raw);

        List<Submission> saved = capturedSaveAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getProblemId()).isEqualTo("add-two-numbers");
        assertThat(saved.get(0).isCountedForTarget()).isFalse();
    }

    @Test
    void emptyBatchPersistsNothing() {
        BackfillService service = newService();
        User user = userWithId(5L);

        service.persistBackfill(user, PLATFORM, List.of());

        verify(submissionRepository, never()).saveAll(any());
        verify(submissionRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // runBackfill: onboarding completion (task 4.3) and partial-failure
    // tolerance (task 4.4).
    // ------------------------------------------------------------------

    /** A minimal fake fetcher bound to one platform, with a scripted response. */
    private static SubmissionFetcher fetcherFor(Platform platform, List<RawSubmission> result) {
        return new SubmissionFetcher() {
            @Override
            public List<RawSubmission> fetchRecent(String username) {
                return result;
            }

            @Override
            public Platform platform() {
                return platform;
            }
        };
    }

    /** A fake fetcher bound to one platform that always throws. */
    private static SubmissionFetcher failingFetcherFor(Platform platform, RuntimeException error) {
        return new SubmissionFetcher() {
            @Override
            public List<RawSubmission> fetchRecent(String username) {
                throw error;
            }

            @Override
            public Platform platform() {
                return platform;
            }
        };
    }

    private User linkedUser(long id) {
        User user = userWithId(id);
        user.setLeetcodeUsername("lc-" + id);
        user.setCodeforcesUsername("cf-" + id);
        user.setGfgUsername("gfg-" + id);
        return user;
    }

    @Test
    void runBackfillMarksOnboardingCompleteWhenAllPlatformsSucceed() {
        User user = linkedUser(10L);
        List<SubmissionFetcher> fetchers = List.of(
                fetcherFor(Platform.LEETCODE, List.of()),
                fetcherFor(Platform.CODEFORCES, List.of()),
                fetcherFor(Platform.GFG, List.of()));
        BackfillService service = new BackfillService(userRepository, submissionRepository, fetchers);

        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        service.runBackfill(10L);

        assertThat(user.isOnboardingComplete()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void runBackfillStillCompletesOnboardingWhenOnePlatformFails() {
        User user = linkedUser(11L);
        // GFG scrape fails; LeetCode/CF succeed. Onboarding must still complete.
        List<SubmissionFetcher> fetchers = List.of(
                fetcherFor(Platform.LEETCODE, List.of()),
                fetcherFor(Platform.CODEFORCES, List.of()),
                failingFetcherFor(Platform.GFG, new ScrapeException("GFG markup changed")));
        BackfillService service = new BackfillService(userRepository, submissionRepository, fetchers);

        when(userRepository.findById(11L)).thenReturn(Optional.of(user));

        // Should not throw despite the GFG failure.
        service.runBackfill(11L);

        assertThat(user.isOnboardingComplete()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void backfillPlatformReturnsFalseOnFailureAndTrueOnSuccess() {
        User user = linkedUser(12L);
        List<SubmissionFetcher> fetchers = List.of(
                fetcherFor(Platform.LEETCODE, List.of()),
                failingFetcherFor(Platform.GFG, new ScrapeException("boom")));
        BackfillService service = new BackfillService(userRepository, submissionRepository, fetchers);

        assertThat(service.backfillPlatform(user, Platform.LEETCODE, "lc")).isTrue();
        assertThat(service.backfillPlatform(user, Platform.GFG, "gfg")).isFalse();
        // Unlinked platform (blank username) is not a failure.
        assertThat(service.backfillPlatform(user, Platform.CODEFORCES, "  ")).isTrue();
    }

    @Test
    void runBackfillSkipsUnknownUserWithoutSaving() {
        BackfillService service = new BackfillService(
                userRepository, submissionRepository, List.<SubmissionFetcher>of());
        lenient().when(userRepository.findById(any())).thenReturn(Optional.empty());

        service.runBackfill(999L);

        verify(userRepository, never()).save(any());
    }
}
