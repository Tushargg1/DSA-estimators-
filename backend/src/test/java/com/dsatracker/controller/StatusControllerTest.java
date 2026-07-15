package com.dsatracker.controller;

import com.dsatracker.model.Platform;
import com.dsatracker.model.PollStatus;
import com.dsatracker.repository.PollStatusRepository;
import com.dsatracker.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link StatusController} (task 8.8, Requirement 9.2).
 *
 * <p>{@link WebMvcTest} loads only the MVC slice; the {@link PollStatusRepository}
 * is a Mockito mock, so the test is database-free and never touches a live
 * platform API. It pins the three behaviours the frontend depends on:
 * <ul>
 *   <li>one entry is returned per platform,</li>
 *   <li>platforms with no stored {@code poll_status} row are null-filled (not omitted), and</li>
 *   <li>a platform with a recorded failure surfaces its {@code lastFailureReason}.</li>
 * </ul>
 */
@WebMvcTest(StatusController.class)
@AutoConfigureMockMvc(addFilters = false)
class StatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PollStatusRepository pollStatusRepository;

    @MockitoBean
    private TokenService tokenService;

    @Test
    void returnsOneEntryPerPlatformWithStoredRowsAndNullFilledGaps() throws Exception {
        // LEETCODE succeeded; CODEFORCES failed; GFG has no stored row at all.
        Instant success = Instant.parse("2024-01-01T10:00:00Z");
        Instant failure = Instant.parse("2024-01-01T11:00:00Z");

        PollStatus leetcode = pollStatus(Platform.LEETCODE, success, null, null);
        PollStatus codeforces = pollStatus(Platform.CODEFORCES, null, failure,
                "GFG_PARSE_FAILURE: markup changed");

        when(pollStatusRepository.findAll()).thenReturn(List.of(leetcode, codeforces));

        mockMvc.perform(get("/api/status/poll"))
                .andExpect(status().isOk())
                // Always all three platforms, in enum declaration order.
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].platform").value("LEETCODE"))
                .andExpect(jsonPath("$[0].lastSuccessAt").value("2024-01-01T10:00:00Z"))
                .andExpect(jsonPath("$[0].lastFailureAt").doesNotExist())
                .andExpect(jsonPath("$[0].minutesSinceLastSuccess").isNumber())
                // CODEFORCES: failure reason is surfaced, no success timestamp.
                .andExpect(jsonPath("$[1].platform").value("CODEFORCES"))
                .andExpect(jsonPath("$[1].lastSuccessAt").doesNotExist())
                .andExpect(jsonPath("$[1].lastFailureAt").value("2024-01-01T11:00:00Z"))
                .andExpect(jsonPath("$[1].lastFailureReason").value("GFG_PARSE_FAILURE: markup changed"))
                .andExpect(jsonPath("$[1].minutesSinceLastSuccess").doesNotExist())
                // GFG: null-filled placeholder since no stored row exists.
                .andExpect(jsonPath("$[2].platform").value("GFG"))
                .andExpect(jsonPath("$[2].lastSuccessAt").doesNotExist())
                .andExpect(jsonPath("$[2].lastFailureAt").doesNotExist())
                .andExpect(jsonPath("$[2].lastFailureReason").doesNotExist())
                .andExpect(jsonPath("$[2].minutesSinceLastSuccess").doesNotExist());
    }

    @Test
    void returnsAllPlatformsNullFilledWhenTableIsEmpty() throws Exception {
        when(pollStatusRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/status/poll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].platform").value("LEETCODE"))
                .andExpect(jsonPath("$[0].lastSuccessAt").doesNotExist())
                .andExpect(jsonPath("$[1].platform").value("CODEFORCES"))
                .andExpect(jsonPath("$[2].platform").value("GFG"));
    }

    private static PollStatus pollStatus(Platform platform, Instant success, Instant failure,
                                         String failureReason) {
        PollStatus status = new PollStatus();
        status.setPlatform(platform);
        status.setLastSuccessAt(success);
        status.setLastFailureAt(failure);
        status.setLastFailureReason(failureReason);
        return status;
    }
}
