package com.dsatracker.web;

import com.dsatracker.model.Submission;
import com.dsatracker.model.User;
import com.dsatracker.security.AccessService;
import com.dsatracker.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for user onboarding (task 8.1).
 *
 * <p>Thin by design: request handling delegates to {@link UserService}, which
 * owns validation, persistence, and backfill triggering. The controller only
 * maps HTTP concerns (status codes, error body shape).
 *
 * <p><b>Endpoint:</b> {@code POST /api/users} — creates a user and kicks off the
 * one-time backfill (Requirements 1.1–1.4). Returns {@code 201 Created} with the
 * created {@link UserResponse}. Validation failures (Requirements 1.2, 1.3) map
 * to {@code 422 Unprocessable Entity} with a per-field {@link ErrorResponse}.
 *
 * <p>Endpoints are authenticated. Profile and submission reads are limited to
 * self or co-members; target updates are self-only.
 *
 * <p>CORS is configured globally in task 12.4; no per-controller CORS is added here.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final AccessService access;

    public UserController(UserService userService, AccessService access) {
        this.userService = userService;
        this.access = access;
    }

    /**
     * Creates a user and triggers their async history backfill.
     *
     * @param request onboarding payload (name, email, optional platform usernames)
     * @return {@code 201 Created} with the created user
     */
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody CreateUserRequest request) {
        User user = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    /**
     * Returns a user's profile (task 8.2).
     *
     * @param id the user id
     * @return {@code 200 OK} with the profile, or {@code 404 Not Found} if missing
     */
    @GetMapping("/{id}")
    public UserResponse getUser(@PathVariable Long id, Authentication authentication) {
        access.requireVisibleUser(access.userId(authentication), id);
        return UserResponse.from(userService.getUser(id));
    }

    /**
     * Updates a user's daily target (task 8.3, Requirement 4.1).
     *
     * @param id      the user id
     * @param request body carrying the new {@code target} ({@code >= 1})
     * @return {@code 200 OK} with the updated profile; {@code 400} if the target is
     *         below 1, {@code 404} if the user is missing
     */
    @PutMapping("/{id}/target")
    public UserResponse updateTarget(@PathVariable Long id,
                                     @RequestBody UpdateTargetRequest request,
                                     Authentication authentication) {
        access.requireSelf(access.userId(authentication), id);
        User updated = userService.updateDailyTarget(id, request.target());
        return UserResponse.from(updated);
    }

    /**
     * Returns a paginated list of a user's submissions (task 8.7,
     * Requirements 8.1, 8.2).
     *
     * <p>Defaults to page size 20, sorted by {@code solvedAtUtc} descending
     * (most recent first). Clients override with {@code ?page=&size=&sort=}.
     *
     * @param id       the user id
     * @param pageable resolved from the request (defaults applied)
     * @return {@code 200 OK} with a {@link PageResponse} of submissions, or
     *         {@code 404 Not Found} if the user is missing
     */
    @GetMapping("/{id}/submissions")
    public PageResponse<SubmissionResponse> getSubmissions(
            @PathVariable Long id,
            @RequestParam(value = "groupId", required = false) Long groupId,
            @PageableDefault(size = 20, sort = "solvedAtUtc", direction = Sort.Direction.DESC)
            Pageable pageable,
            Authentication authentication) {
        Long actorId = access.userId(authentication);
        Page<Submission> page;
        if (groupId != null) {
            java.time.Instant joinedAt = access.requireGroupUser(actorId, id, groupId);
            page = userService.getSubmissionsSince(id, joinedAt, pageable);
        } else {
            access.requireVisibleUser(actorId, id);
            page = userService.getSubmissions(id, pageable);
        }
        return PageResponse.from(page, SubmissionResponse::from);
    }

    /**
     * Maps a {@link ValidationException} to {@code 422 Unprocessable Entity} with
     * a per-field error body so the client can show errors inline (Requirement 1.3).
     */
    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleValidation(ValidationException ex) {
        return new ErrorResponse(ex.getErrors());
    }
}
