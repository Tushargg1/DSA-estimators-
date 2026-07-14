package com.dsatracker.web;

import com.dsatracker.model.Platform;
import com.dsatracker.model.Submission;
import com.dsatracker.model.User;
import com.dsatracker.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link UserController} (tasks 8.1, 8.2, 8.3, 8.7).
 *
 * <p>Uses {@code @WebMvcTest} so only the controller + JSON/HTTP plumbing load;
 * {@link UserService} is a Mockito mock, so no database, backfill job, or live
 * platform call is exercised. These tests pin the HTTP contract (status codes and
 * response body shape) the frontend depends on; the service's business logic is
 * unit-tested separately in {@code UserServiceTest}.
 */
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    private static User sampleUser() {
        User user = new User();
        user.setId(42L);
        user.setName("Ada");
        user.setEmail("ada@example.com");
        user.setLeetcodeUsername("ada_lc");
        user.setCodeforcesUsername("ada_cf");
        user.setGfgUsername(null);
        user.setDailyTarget(5);
        user.setOnboardingComplete(false);
        user.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        return user;
    }

    // ---- 8.1 POST /api/users ------------------------------------------------

    @Test
    void createUserReturns201WithProfile() throws Exception {
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(sampleUser());

        CreateUserRequest request = new CreateUserRequest(
                "Ada", "ada@example.com", "ada_lc", "ada_cf", null, null);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.name").value("Ada"))
                .andExpect(jsonPath("$.dailyTarget").value(5))
                .andExpect(jsonPath("$.onboardingComplete").value(false));

        // The controller must delegate creation (which triggers backfill) to the service.
        verify(userService).createUser(any(CreateUserRequest.class));
    }

    @Test
    void createUserWithInvalidPlatformReturnsPerFieldErrors() throws Exception {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("leetcodeUsername", "LeetCode username 'ghost' could not be verified.");
        errors.put("gfgUsername", "GeeksforGeeks profile 'ghost' could not be verified.");
        when(userService.createUser(any(CreateUserRequest.class)))
                .thenThrow(new ValidationException(errors));

        CreateUserRequest request = new CreateUserRequest(
                "Ada", "ada@example.com", "ghost", null, "ghost", null);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.leetcodeUsername").value(
                        "LeetCode username 'ghost' could not be verified."))
                .andExpect(jsonPath("$.errors.gfgUsername").value(
                        "GeeksforGeeks profile 'ghost' could not be verified."));
    }

    @Test
    void createUserWithDuplicateEmailReturnsFieldError() throws Exception {
        Map<String, String> errors = Map.of(
                "email", "An account with this email already exists.");
        when(userService.createUser(any(CreateUserRequest.class)))
                .thenThrow(new ValidationException(errors));

        CreateUserRequest request = new CreateUserRequest(
                "Ada", "taken@example.com", null, null, null, null);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.email").value(
                        "An account with this email already exists."));
    }

    // ---- 8.2 GET /api/users/{id} -------------------------------------------

    @Test
    void getUserReturnsProfile() throws Exception {
        when(userService.getUser(42L)).thenReturn(sampleUser());

        mockMvc.perform(get("/api/users/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.leetcodeUsername").value("ada_lc"));
    }

    @Test
    void getUnknownUserReturns404() throws Exception {
        when(userService.getUser(99L))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "User 99 not found."));

        mockMvc.perform(get("/api/users/99"))
                .andExpect(status().isNotFound());
    }

    // ---- 8.3 PUT /api/users/{id}/target ------------------------------------

    @Test
    void updateTargetReturnsUpdatedProfile() throws Exception {
        User updated = sampleUser();
        updated.setDailyTarget(8);
        when(userService.updateDailyTarget(eq(42L), eq(8))).thenReturn(updated);

        mockMvc.perform(put("/api/users/42/target")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTargetRequest(8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyTarget").value(8));
    }

    @Test
    void updateTargetBelowMinimumReturns400() throws Exception {
        when(userService.updateDailyTarget(eq(42L), eq(0)))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Daily target must be at least 1."));

        mockMvc.perform(put("/api/users/42/target")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTargetRequest(0))))
                .andExpect(status().isBadRequest());
    }

    // ---- 8.7 GET /api/users/{id}/submissions -------------------------------

    @Test
    void getSubmissionsReturnsPagedFlags() throws Exception {
        Submission first = new Submission();
        first.setProblemId("two-sum");
        first.setProblemName("Two Sum");
        first.setPlatform(Platform.LEETCODE);
        first.setDifficulty("Easy");
        first.setTags(List.of("array", "hash-table"));
        first.setSolvedAtUtc(Instant.parse("2024-01-02T10:00:00Z"));
        first.setFirstAttempt(true);
        first.setCountedForTarget(true);

        Submission gfg = new Submission();
        gfg.setProblemId("some-gfg-problem");
        gfg.setProblemName("Some GFG Problem");
        gfg.setPlatform(Platform.GFG);
        gfg.setDifficulty(null); // missing metadata -> null passes through
        gfg.setTags(null);
        gfg.setSolvedAtUtc(Instant.parse("2024-01-01T10:00:00Z"));
        gfg.setFirstAttempt(false);
        gfg.setCountedForTarget(false);

        Pageable pageable = PageRequest.of(0, 20);
        Page<Submission> page = new PageImpl<>(List.of(first, gfg), pageable, 2);
        when(userService.getSubmissions(eq(1L), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/users/1/submissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.content[0].problemName").value("Two Sum"))
                .andExpect(jsonPath("$.content[0].platform").value("LEETCODE"))
                .andExpect(jsonPath("$.content[0].isFirstAttempt").value(true))
                .andExpect(jsonPath("$.content[0].countedForTarget").value(true))
                .andExpect(jsonPath("$.content[1].difficulty").isEmpty())
                .andExpect(jsonPath("$.content[1].isFirstAttempt").value(false));
    }

    @Test
    void getSubmissionsForUnknownUserReturns404() throws Exception {
        when(userService.getSubmissions(eq(99L), any(Pageable.class)))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "User 99 not found."));

        mockMvc.perform(get("/api/users/99/submissions"))
                .andExpect(status().isNotFound());
    }
}
