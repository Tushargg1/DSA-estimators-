package com.dsatracker.service;

import com.dsatracker.adapter.SubmissionFetcher;
import com.dsatracker.adapter.exception.RateLimitException;
import com.dsatracker.adapter.exception.ScrapeException;
import com.dsatracker.model.Platform;
import com.dsatracker.model.User;
import com.dsatracker.repository.UserRepository;
import com.dsatracker.web.CreateUserRequest;
import com.dsatracker.web.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService} (task 8.1) — database-free: the repository,
 * backfill service, and the three platform adapters are all Mockito mocks.
 *
 * <p>Covers the key onboarding behaviours from Requirements 1.1–1.4:
 * duplicate-email rejection, per-field platform validation errors, the GFG
 * scrape-failure decision, and the happy path (save + async backfill trigger).
 */
class UserServiceTest {

    private UserRepository userRepository;
    private com.dsatracker.repository.SubmissionRepository submissionRepository;
    private BackfillService backfillService;
    private SubmissionFetcher leetcode;
    private SubmissionFetcher codeforces;
    private SubmissionFetcher gfg;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        submissionRepository = mock(com.dsatracker.repository.SubmissionRepository.class);
        backfillService = mock(BackfillService.class);
        leetcode = mock(SubmissionFetcher.class);
        codeforces = mock(SubmissionFetcher.class);
        gfg = mock(SubmissionFetcher.class);

        when(leetcode.platform()).thenReturn(Platform.LEETCODE);
        when(codeforces.platform()).thenReturn(Platform.CODEFORCES);
        when(gfg.platform()).thenReturn(Platform.GFG);

        userService = new UserService(userRepository, submissionRepository, backfillService,
                List.of(leetcode, codeforces, gfg));
    }

    @Test
    void rejectsDuplicateEmail() {
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        CreateUserRequest request = new CreateUserRequest(
                "Ada", "taken@example.com", null, null, null, null);

        ValidationException ex = catchThrowableOfType(
                () -> userService.createUser(request), ValidationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrors()).containsKey("email");
        verify(userRepository, never()).save(any());
        verify(backfillService, never()).runBackfill(any());
    }

    @Test
    void requiresNameAndEmail() {
        CreateUserRequest request = new CreateUserRequest("  ", "  ", null, null, null, null);

        ValidationException ex = catchThrowableOfType(
                () -> userService.createUser(request), ValidationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrors()).containsKeys("name", "email");
        verify(userRepository, never()).save(any());
        verify(backfillService, never()).runBackfill(any());
    }

    @Test
    void invalidLeetCodeUsernameProducesPerFieldError() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(leetcode.fetchRecent("ghost"))
                .thenThrow(new RuntimeException("LeetCode GraphQL returned errors"));

        CreateUserRequest request = new CreateUserRequest(
                "Ada", "ada@example.com", "ghost", null, null, null);

        ValidationException ex = catchThrowableOfType(
                () -> userService.createUser(request), ValidationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrors()).containsOnlyKeys("leetcodeUsername");
        assertThat(ex.getErrors().get("leetcodeUsername")).contains("LeetCode");
        verify(userRepository, never()).save(any());
        verify(backfillService, never()).runBackfill(any());
    }

    @Test
    void gfgScrapeFailureProducesPerFieldError() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(gfg.fetchRecent("ada"))
                .thenThrow(new ScrapeException("GFG page appears client-rendered"));

        CreateUserRequest request = new CreateUserRequest(
                "Ada", "ada@example.com", null, null, "ada", null);

        ValidationException ex = catchThrowableOfType(
                () -> userService.createUser(request), ValidationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrors()).containsOnlyKeys("gfgUsername");
        assertThat(ex.getErrors().get("gfgUsername")).contains("GeeksforGeeks");
    }

    @Test
    void collectsMultiplePlatformErrors() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(leetcode.fetchRecent("bad-lc")).thenThrow(new RuntimeException("nope"));
        when(codeforces.fetchRecent("bad-cf")).thenThrow(new RateLimitException("429"));

        CreateUserRequest request = new CreateUserRequest(
                "Ada", "ada@example.com", "bad-lc", "bad-cf", null, null);

        ValidationException ex = catchThrowableOfType(
                () -> userService.createUser(request), ValidationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrors()).containsOnlyKeys("leetcodeUsername", "codeforcesUsername");
    }

    @Test
    void happyPathSavesUserAndTriggersBackfill() {
        when(userRepository.existsByEmail("ada@example.com")).thenReturn(false);
        when(leetcode.fetchRecent("ada_lc")).thenReturn(List.of());
        when(codeforces.fetchRecent("ada_cf")).thenReturn(List.of());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(42L);
            return u;
        });

        CreateUserRequest request = new CreateUserRequest(
                "Ada", "ada@example.com", "ada_lc", "ada_cf", null, null);

        User created = userService.createUser(request);

        assertThat(created.getId()).isEqualTo(42L);
        assertThat(created.getName()).isEqualTo("Ada");
        assertThat(created.getEmail()).isEqualTo("ada@example.com");
        assertThat(created.getLeetcodeUsername()).isEqualTo("ada_lc");
        assertThat(created.getCodeforcesUsername()).isEqualTo("ada_cf");
        assertThat(created.getGfgUsername()).isNull();
        assertThat(created.isOnboardingComplete()).isFalse();
        assertThat(created.getDailyTarget()).isEqualTo(5);

        verify(userRepository, times(1)).save(any(User.class));
        verify(backfillService, times(1)).runBackfill(42L);
    }

    @Test
    void usesProvidedDailyTargetWhenValid() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(7L);
            return u;
        });

        CreateUserRequest request = new CreateUserRequest(
                "Ada", "ada@example.com", null, null, null, 10);

        User created = userService.createUser(request);

        assertThat(created.getDailyTarget()).isEqualTo(10);
        verify(backfillService).runBackfill(7L);
    }

    @Test
    void skipsPlatformValidationWhenUsernameBlank() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1L);
            return u;
        });
        // Make any adapter call fail loudly so the test proves it's never invoked.
        doThrow(new RuntimeException("should not be called"))
                .when(leetcode).fetchRecent(anyString());

        CreateUserRequest request = new CreateUserRequest(
                "Ada", "ada@example.com", "   ", null, null, null);

        User created = userService.createUser(request);

        assertThat(created.getLeetcodeUsername()).isNull();
        verify(leetcode, never()).fetchRecent(anyString());
    }
}
