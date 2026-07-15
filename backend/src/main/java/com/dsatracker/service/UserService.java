package com.dsatracker.service;

import com.dsatracker.adapter.SubmissionFetcher;
import com.dsatracker.adapter.exception.RateLimitException;
import com.dsatracker.adapter.exception.ScrapeException;
import com.dsatracker.model.Platform;
import com.dsatracker.model.Submission;
import com.dsatracker.model.User;
import com.dsatracker.repository.DailyCountRepository;
import com.dsatracker.repository.SubmissionRepository;
import com.dsatracker.repository.UserRepository;
import com.dsatracker.web.CreateUserRequest;
import com.dsatracker.web.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates and validates users during onboarding (task 8.1), keeping the REST
 * controller thin. Reused by later user endpoints.
 *
 * <h2>Validation (Requirements 1.2, 1.3)</h2>
 * {@link #createUser(CreateUserRequest)} validates before persisting:
 * <ul>
 *   <li>{@code name} and {@code email} are required.</li>
 *   <li>{@code email} must be unique ({@link UserRepository#existsByEmail}).</li>
 *   <li>Every <em>provided</em> platform username is verified by a live test call
 *       to that platform's {@link SubmissionFetcher#fetchRecent(String)}: a
 *       successful call (even an empty result) means the profile is reachable;
 *       a thrown exception means the username is invalid, private, or the
 *       platform is unreachable.</li>
 * </ul>
 * All failures are collected into a {@code field -> message} map and thrown as a
 * single {@link ValidationException} so the client can show every error at once
 * (rather than failing on the first). Validation never fails the request with an
 * unhandled 500 — even a fragile GFG scrape is caught and mapped to a field error.
 *
 * <h2>GFG hard-block vs soft-warning decision</h2>
 * The spec notes GFG is fragile and its failures must be visible but non-fatal.
 * For onboarding validation we therefore:
 * <ul>
 *   <li>Validate LeetCode and Codeforces <b>strictly</b> — a failure there blocks
 *       creation, since those are reliable APIs and a failure almost always means
 *       a genuinely bad handle.</li>
 *   <li>Treat GFG as a validation error <b>for now</b> (consistent with
 *       Requirement 1.3) but keep the decision isolated behind
 *       {@link #GFG_VALIDATION_HARD_BLOCKS} and in a dedicated branch, so it can
 *       be softened to a non-blocking warning (create the user anyway, surface
 *       the GFG issue elsewhere) by flipping a single constant without touching
 *       the LeetCode/Codeforces path.</li>
 * </ul>
 * A {@link ScrapeException} (GFG page client-rendered/unreachable) is mapped to a
 * clear per-field message and never crashes the request.
 *
 * <h2>On success (Requirement 1.4)</h2>
 * The user is saved with {@code onboardingComplete = false} and the default
 * {@code dailyTarget} of 5 (unless the request overrides it), then the async
 * one-time {@link BackfillService#runBackfill(Long)} is triggered on the saved
 * id. Backfill flips {@code onboardingComplete = true} when it finishes.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** Default per-user daily target when the request does not specify one (Requirement 4.1). */
    static final int DEFAULT_DAILY_TARGET = 5;

    /**
     * Whether a failed GFG profile check blocks user creation.
     *
     * <p>{@code true} today (Requirement 1.3: reject a field whose profile can't
     * be verified). Isolated here so the fragile GFG platform can be downgraded
     * to a soft, non-blocking warning later — flip to {@code false} and GFG
     * verification failures will be logged/surfaced without blocking onboarding,
     * while LeetCode/Codeforces stay strict.
     */
    static final boolean GFG_VALIDATION_HARD_BLOCKS = true;

    private final UserRepository userRepository;
    private final SubmissionRepository submissionRepository;
    private final DailyCountRepository dailyCountRepository;
    private final BackfillService backfillService;

    /** Adapter per platform, indexed once from all injected {@link SubmissionFetcher}s. */
    private final Map<Platform, SubmissionFetcher> fetchersByPlatform;

    public UserService(UserRepository userRepository,
                       SubmissionRepository submissionRepository,
                       DailyCountRepository dailyCountRepository,
                       BackfillService backfillService,
                       List<SubmissionFetcher> fetchers) {
        this.userRepository = userRepository;
        this.submissionRepository = submissionRepository;
        this.dailyCountRepository = dailyCountRepository;
        this.backfillService = backfillService;
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

    /**
     * Validates and creates a user, then triggers the async backfill.
     *
     * @param request the onboarding request
     * @return the persisted user (with generated id, {@code onboardingComplete = false})
     * @throws ValidationException if any field is invalid; carries a per-field
     *         message map so the client can show all errors at once
     */
    public User createUser(CreateUserRequest request) {
        return createUser(request, null, false);
    }

    /** Creates a user with optional credentials; hashes are never exposed in API responses. */
    public User createUser(CreateUserRequest request, String passwordHash, boolean credentialsEnabled) {
        Map<String, String> errors = new LinkedHashMap<>();

        String name = trimToNull(request.name());
        if (name == null) {
            errors.put("name", "Display name is required.");
        }

        String email = normalizeEmail(request.email());
        if (email == null) {
            errors.put("email", "Email is required.");
        } else if (userRepository.existsByEmail(email)) {
            errors.put("email", "An account with this email already exists.");
        }

        // Verify each linked platform independently and collect all failures, so
        // one bad handle doesn't hide another and the client sees every error.
        String leetcode = trimToNull(request.leetcodeUsername());
        String codeforces = trimToNull(request.codeforcesUsername());
        String gfg = trimToNull(request.gfgUsername());

        validatePlatform(errors, "leetcodeUsername", Platform.LEETCODE, leetcode);
        validatePlatform(errors, "codeforcesUsername", Platform.CODEFORCES, codeforces);
        validatePlatform(errors, "gfgUsername", Platform.GFG, gfg);

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setCredentialsEnabled(credentialsEnabled);
        user.setLeetcodeUsername(leetcode);
        user.setCodeforcesUsername(codeforces);
        user.setGfgUsername(gfg);
        user.setDailyTarget(resolveDailyTarget(request.dailyTarget()));
        user.setOnboardingComplete(false);
        user.setCreatedAt(Instant.now());

        User saved = userRepository.save(user);
        log.info("Created user id={} ({}), triggering backfill", saved.getId(), saved.getName());

        // Requirement 1.4: kick off the one-time historical import. This is @Async
        // on BackfillService, so it returns immediately and does not block the
        // create response.
        backfillService.runBackfill(saved.getId());

        return saved;
    }

    /**
     * Loads a user's profile (task 8.2, {@code GET /api/users/{id}}).
     *
     * @param id the user id
     * @return the user
     * @throws ResponseStatusException {@code 404 Not Found} if no such user exists
     */
    public User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User " + id + " not found."));
    }

    /**
     * Updates a user's daily target (task 8.3, {@code PUT /api/users/{id}/target},
     * Requirement 4.1).
     *
     * <p>The target must be present and at least {@code 1}; a missing or
     * below-minimum value is rejected with {@code 400 Bad Request} so a user can
     * never set a zero/negative target that would make every day trivially a
     * "hit" (or impossible to compute a streak against).
     *
     * @param id     the user id
     * @param target the requested daily target ({@code >= 1})
     * @return the updated user
     * @throws ResponseStatusException {@code 404 Not Found} if the user is missing,
     *         or {@code 400 Bad Request} if {@code target} is null or {@code < 1}
     */
    @Transactional
    public User updateDailyTarget(Long id, Integer target) {
        if (target == null || target < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Daily target must be at least 1.");
        }
        User user = getUser(id);
        user.setDailyTarget(target);
        User saved = userRepository.save(user);
        int recomputed = dailyCountRepository.recomputeTargetHit(id, target);
        log.info("Updated daily target for user id={} to {} and recomputed {} daily row(s)",
                id, target, recomputed);
        return saved;
    }

    /**
     * Returns a page of a user's submissions, most-recent-first by default
     * (task 8.7, {@code GET /api/users/{id}/submissions}, Requirements 8.1, 8.2).
     *
     * <p>Validates the user exists first so a request for an unknown user returns
     * {@code 404 Not Found} rather than a misleading empty page.
     *
     * @param id       the user id
     * @param pageable page/size/sort supplied by the request
     * @return a page of {@link Submission} entities for that user
     * @throws ResponseStatusException {@code 404 Not Found} if the user is missing
     */
    public Page<Submission> getSubmissions(Long id, Pageable pageable) {
        if (!userRepository.existsById(id)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "User " + id + " not found.");
        }
        return submissionRepository.findByUserId(id, pageable);
    }

    /**
     * Verifies a single platform username by a test call to its adapter, adding a
     * per-field error to {@code errors} on failure. No-op when {@code username}
     * is null/blank (the platform wasn't linked) or no adapter is registered.
     *
     * <p>A successful {@code fetchRecent} (even empty) proves the profile is
     * reachable. Exceptions are classified into a clear, platform-specific
     * message. GFG failures honor {@link #GFG_VALIDATION_HARD_BLOCKS}.
     */
    private void validatePlatform(Map<String, String> errors, String field,
                                  Platform platform, String username) {
        if (username == null) {
            return;
        }
        SubmissionFetcher fetcher = fetchersByPlatform.get(platform);
        if (fetcher == null) {
            log.warn("No adapter registered for platform {}, skipping validation of field '{}'",
                    platform, field);
            return;
        }

        try {
            fetcher.fetchRecent(username);
            // Reachable (empty or not) → valid.
        } catch (ScrapeException ex) {
            // GFG-specific: page unreachable/client-rendered/markup changed.
            log.warn("GFG validation failed for '{}': {}", username, ex.getMessage());
            if (GFG_VALIDATION_HARD_BLOCKS) {
                errors.put(field, "GeeksforGeeks profile '" + username
                        + "' could not be verified. Check that the profile exists and is public.");
            }
            // else: soft mode — intentionally do not block; failure is logged and
            // would be surfaced via the poll-status page in a later iteration.
        } catch (RateLimitException ex) {
            log.warn("Rate-limited validating {} username '{}': {}", platform, username, ex.getMessage());
            errors.put(field, platformLabel(platform)
                    + " is temporarily rate-limiting requests, so '" + username
                    + "' could not be verified right now. Please try again shortly.");
        } catch (RuntimeException ex) {
            log.warn("Validation failed for {} username '{}': {}", platform, username, ex.getMessage());
            errors.put(field, platformLabel(platform) + " username '" + username
                    + "' could not be verified. Check that the profile exists and is public.");
        }
    }

    private int resolveDailyTarget(Integer requested) {
        if (requested == null) {
            return DEFAULT_DAILY_TARGET;
        }
        // Guard against nonsensical targets; fall back to the default.
        return requested > 0 ? requested : DEFAULT_DAILY_TARGET;
    }

    private static String platformLabel(Platform platform) {
        return switch (platform) {
            case LEETCODE -> "LeetCode";
            case CODEFORCES -> "Codeforces";
            case GFG -> "GeeksforGeeks";
        };
    }

    private static String normalizeEmail(String value) {
        String email = trimToNull(value);
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
