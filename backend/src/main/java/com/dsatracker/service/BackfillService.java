package com.dsatracker.service;

import com.dsatracker.adapter.RawSubmission;
import com.dsatracker.adapter.SubmissionFetcher;
import com.dsatracker.config.AsyncConfig;
import com.dsatracker.model.Platform;
import com.dsatracker.model.Submission;
import com.dsatracker.model.User;
import com.dsatracker.repository.SubmissionRepository;
import com.dsatracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the one-time onboarding backfill: when a user is created, this
 * service asynchronously pulls their existing submission history from every
 * linked platform (Requirement 1.4).
 *
 * <h2>Scope of task 4.1 (this class)</h2>
 * This task establishes the async entry point and the per-platform adapter
 * orchestration. It:
 * <ul>
 *   <li>Loads the user and, for each linked platform username that is
 *       non-null/non-blank, selects the matching {@link SubmissionFetcher} and
 *       gathers that platform's available history.</li>
 *   <li>Routes gathered {@link RawSubmission}s to {@link #persistBackfill} — the
 *       seam that task 4.2 fills in (insert with {@code counted_for_target=false}
 *       and {@code is_first_attempt} computed normally).</li>
 * </ul>
 *
 * <h2>Related tasks now implemented here</h2>
 * <ul>
 *   <li><b>4.2 — persistence:</b> {@link #persistBackfill(User, Platform, List)}
 *       inserts rows with {@code counted_for_target = false} and computed
 *       {@code is_first_attempt}.</li>
 *   <li><b>4.3 — onboarding completion:</b> after all linked platforms are
 *       attempted, {@link #runBackfill} flips {@code onboarding_complete = true}
 *       and persists the user (Requirement 1.6).</li>
 *   <li><b>4.4 — partial-failure handling:</b> {@link #backfillPlatform} isolates
 *       each per-platform attempt so one platform failing (e.g. a GFG scrape)
 *       still lets the others complete and still marks onboarding complete,
 *       logging which platform failed and reporting a succeeded-vs-failed
 *       summary.</li>
 * </ul>
 *
 * <h2>History depth caveat</h2>
 * The {@link SubmissionFetcher} contract exposes only {@link SubmissionFetcher#fetchRecent(String)},
 * which returns a recent window rather than full history. Backfill therefore uses
 * that window as its best-available source today. LeetCode and Codeforces both
 * support paginating further back (Codeforces {@code user.status from/count};
 * LeetCode via larger/paged queries) and GFG is inherently limited to whatever
 * the profile exposes — see the TODO in {@link #gatherHistory} for the pagination
 * follow-up.
 */
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    private final UserRepository userRepository;
    private final SubmissionRepository submissionRepository;

    /** Adapter per platform, built once from all injected {@link SubmissionFetcher}s. */
    private final Map<Platform, SubmissionFetcher> fetchersByPlatform;

    /**
     * Spring injects every {@code @Component} {@link SubmissionFetcher}
     * (LeetCode, Codeforces, GFG) into this list; we index them by
     * {@link SubmissionFetcher#platform()} so a linked username can be routed to
     * the correct adapter without hard-coding concrete types.
     */
    public BackfillService(UserRepository userRepository,
                           SubmissionRepository submissionRepository,
                           List<SubmissionFetcher> fetchers) {
        this.userRepository = userRepository;
        this.submissionRepository = submissionRepository;
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
     * Asynchronously runs the one-time backfill for a newly created user.
     *
     * <p>Runs on the {@link AsyncConfig#BACKFILL_EXECUTOR} pool so the caller
     * (the user-creation request, task 8.1) returns immediately. Requirement 1.4:
     * "WHEN a user is successfully created THEN the system SHALL immediately
     * trigger a one-time backfill job to pull their full existing submission
     * history."
     *
     * @param userId id of the user to backfill; if no such user exists the job
     *               logs a warning and returns (nothing to do)
     */
    @Async(AsyncConfig.BACKFILL_EXECUTOR)
    public void runBackfill(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Backfill requested for unknown user id={}, skipping", userId);
            return;
        }

        log.info("Starting backfill for user id={} ({})", user.getId(), user.getName());

        // Only platforms the user actually linked are attempted; skipped
        // (unlinked) platforms are neither a success nor a failure.
        Map<Platform, String> linkedUsernames = new EnumMap<>(Platform.class);
        putIfLinked(linkedUsernames, Platform.LEETCODE, user.getLeetcodeUsername());
        putIfLinked(linkedUsernames, Platform.CODEFORCES, user.getCodeforcesUsername());
        putIfLinked(linkedUsernames, Platform.GFG, user.getGfgUsername());

        // Task 4.4: attempt every linked platform independently. A failure in one
        // (e.g. a fragile GFG scrape) must not abort the others, so each call is
        // isolated in backfillPlatform and reports success/failure via its return
        // value rather than throwing.
        List<Platform> succeeded = new ArrayList<>();
        List<Platform> failed = new ArrayList<>();
        for (Map.Entry<Platform, String> entry : linkedUsernames.entrySet()) {
            boolean ok = backfillPlatform(user, entry.getKey(), entry.getValue());
            (ok ? succeeded : failed).add(entry.getKey());
        }

        // Task 4.3 + 4.4: mark onboarding complete REGARDLESS of individual
        // platform failures. Rationale: onboarding should not be held hostage by a
        // single fragile platform (GFG in particular) — the failure is logged at
        // WARN and surfaced in the summary below, so it stays visible without
        // blocking the user. Once this flag flips, subsequently POLLED submissions
        // become eligible for counted_for_target = true (that logic lives in
        // section 5; here we only flip the flag). Requirement 1.6.
        user.setOnboardingComplete(true);
        userRepository.save(user);

        log.info("Backfill complete for user id={}: onboarding_complete=true, succeeded={}, failed={}",
                user.getId(), succeeded, failed);
    }

    /** Adds {@code platform -> username} to {@code target} only if the username is linked (non-blank). */
    private static void putIfLinked(Map<Platform, String> target, Platform platform, String username) {
        if (username != null && !username.isBlank()) {
            target.put(platform, username);
        }
    }

    /**
     * Backfills a single platform for a user, if that platform is linked.
     *
     * <p>Skips silently when {@code username} is null/blank (the user did not
     * link that platform) or when no adapter is registered for the platform.
     * Gathers the platform's available history and hands it to
     * {@link #persistBackfill}.
     *
     * <p><b>Task 4.4 — partial-failure isolation:</b> the per-platform work is
     * wrapped in a try/catch here so that a single platform failing (e.g. a GFG
     * scrape throwing {@link com.dsatracker.adapter.exception.ScrapeException},
     * a {@link com.dsatracker.adapter.exception.RateLimitException}, or any other
     * {@link RuntimeException}) is logged at WARN and swallowed rather than
     * propagated. This keeps {@link #runBackfill} able to continue with the other
     * platforms and still complete onboarding.
     *
     * @return {@code true} if the platform was backfilled without error (or was
     *         not linked / had no adapter to attempt); {@code false} if the
     *         attempt failed with an exception. The caller uses this to build the
     *         succeeded-vs-failed summary.
     */
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

            persistBackfill(user, platform, history);
            return true;
        } catch (RuntimeException ex) {
            // Task 4.4: do NOT rethrow — one platform's failure must not abort the
            // others or block onboarding completion. Log which platform failed,
            // for whom, and why, so the failure stays visible.
            log.warn("Backfill failed for platform={} user={}: {}",
                    platform, user.getId(), ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Gathers the available submission history for one platform username.
     *
     * <p>Uses {@link SubmissionFetcher#fetchRecent(String)} as the best-available
     * history source today. This returns only a recent window, not full history.
     *
     * <p>TODO(task 4.x follow-up): deepen history where the platform allows —
     * Codeforces {@code user.status} accepts {@code from}/{@code count} for
     * pagination and LeetCode can be queried in larger/paged batches, so a
     * dedicated paginated fetch could replace {@code fetchRecent} for those two.
     * GFG is limited to whatever the public profile exposes. Kept as
     * {@code fetchRecent} for 4.1 to avoid inventing adapter methods that don't
     * yet exist; see design.md "Platform Adapters".
     */
    List<RawSubmission> gatherHistory(SubmissionFetcher fetcher, String username) {
        return fetcher.fetchRecent(username);
    }

    /**
     * Persists a batch of backfilled submissions for one platform.
     *
     * <p>Backfilled rows are always inserted with {@code counted_for_target =
     * false}: historical imports never score toward a daily target, even when
     * {@code is_first_attempt = true} (Requirements 1.5 / 3.4, design.md
     * Property 3 — "Backfill never scores"). This is the key invariant this
     * method guarantees.
     *
     * <p>{@code is_first_attempt} is computed normally: a row is the first
     * attempt only if the user has never solved this
     * {@code (user_id, platform, problem_id)} before. "Before" spans two sources:
     * <ul>
     *   <li>rows already persisted in the database
     *       ({@link SubmissionRepository#existsByUserIdAndPlatformAndProblemId}), and</li>
     *   <li>earlier rows inserted within <em>this same</em> backfill batch — the
     *       batch is sorted by {@code solvedAt} ascending and a
     *       {@code seenProblemIds} set tracks problems already assigned a
     *       first-attempt in this run, so two historical solves of the same
     *       problem don't both get {@code is_first_attempt = true}. The earliest
     *       occurrence wins.</li>
     * </ul>
     *
     * <p>Idempotency: rows whose natural key
     * {@code (user_id, platform, problem_id, solved_at_utc)} already exists are
     * skipped, so re-running backfill never double-inserts.
     *
     * @param user           the user being onboarded
     * @param platform       the platform these submissions came from
     * @param rawSubmissions the gathered history for that platform (never null)
     */
    @Transactional
    void persistBackfill(User user, Platform platform, List<RawSubmission> rawSubmissions) {
        if (rawSubmissions.isEmpty()) {
            log.debug("persistBackfill: no {} rows to persist for user id={}", platform, user.getId());
            return;
        }

        Long userId = user.getId();

        // Assign first-attempt to the EARLIEST solve of each problem: sort ascending.
        List<RawSubmission> ordered = new ArrayList<>(rawSubmissions);
        ordered.sort(Comparator.comparing(RawSubmission::solvedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        // Problems already granted first-attempt within THIS batch, so a second
        // historical solve of the same problem is is_first_attempt = false.
        Set<String> seenProblemIds = new HashSet<>();

        List<Submission> toInsert = new ArrayList<>();
        int skipped = 0;

        for (RawSubmission raw : ordered) {
            String problemId = raw.problemId();

            // Idempotent dedupe on the natural key: skip rows already persisted.
            if (submissionRepository.existsByUserIdAndPlatformAndProblemIdAndSolvedAtUtc(
                    userId, platform, problemId, raw.solvedAt())) {
                skipped++;
                // Still record it as seen so any later in-batch solve of the same
                // problem is not mistakenly treated as a first attempt.
                seenProblemIds.add(problemId);
                continue;
            }

            // First attempt iff never solved before, in the DB or earlier in this batch.
            boolean firstAttempt =
                    seenProblemIds.add(problemId)
                    && !submissionRepository.existsByUserIdAndPlatformAndProblemId(userId, platform, problemId);

            Submission submission = new Submission();
            submission.setUserId(userId);
            submission.setPlatform(platform);
            submission.setProblemId(problemId);
            submission.setProblemName(raw.problemName());
            submission.setDifficulty(raw.difficulty());
            submission.setTags(raw.tags());
            submission.setSolvedAtUtc(raw.solvedAt());
            submission.setFirstAttempt(firstAttempt);
            // Invariant (design.md Property 3): backfilled rows never score.
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
