package com.dsatracker.controller;

import com.dsatracker.dto.GroupResponse;
import com.dsatracker.dto.HistoryResponse;
import com.dsatracker.dto.LeaderboardResponse;
import com.dsatracker.security.AccessService;
import com.dsatracker.service.GroupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for {@link GroupController} using a standalone {@link MockMvc}
 * (no Spring context, no datasource). {@link GroupService} is mocked; these tests
 * assert status-code mapping and JSON shape only.
 */
class GroupControllerTest {

    private GroupService groupService;
    private AccessService access;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        groupService = Mockito.mock(GroupService.class);
        access = Mockito.mock(AccessService.class);
        when(access.userId(Mockito.nullable(org.springframework.security.core.Authentication.class)))
                .thenReturn(7L);
        // Match Spring Boot's Jackson config so LocalDate serializes as an ISO
        // string ("2024-06-10") rather than a numeric array in this standalone setup.
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        com.dsatracker.service.GroupTargetService targets =
                Mockito.mock(com.dsatracker.service.GroupTargetService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new GroupController(groupService, targets, access))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void createGroupReturns201WithBody() throws Exception {
        when(groupService.createGroup(any(), eq(7L)))
                .thenReturn(new GroupResponse(100L, "Friends", "ABC234", 7L, 3, "AUTO"));

        mockMvc.perform(post("/api/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Friends\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.inviteCode").value("ABC234"))
                .andExpect(jsonPath("$.createdBy").value(7));
    }

    @Test
    void joinGroupReturns200() throws Exception {
        when(groupService.joinGroup(any(), eq(7L)))
                .thenReturn(new GroupResponse(50L, "Squad", "XYZ789", 1L, 3, "AUTO"));

        mockMvc.perform(post("/api/groups/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"XYZ789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(50));
    }

    @Test
    void leaderboardReturns200() throws Exception {
        when(groupService.getLeaderboard(50L)).thenReturn(new LeaderboardResponse(
                50L, "Squad", List.of(new LeaderboardResponse.MemberEntry(
                        1L, "Alice", 3, 5, 2, 8, 42L))));

        mockMvc.perform(get("/api/groups/50/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(50))
                .andExpect(jsonPath("$.members[0].userName").value("Alice"))
                .andExpect(jsonPath("$.members[0].totalSolved").value(42));
    }

    @Test
    void leaderboardForMissingGroupReturns404() throws Exception {
        when(groupService.getLeaderboard(404L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Group 404 not found"));

        mockMvc.perform(get("/api/groups/404/leaderboard"))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyReturns200() throws Exception {
        LocalDate date = LocalDate.of(2024, 6, 10);
        when(groupService.getHistory(eq(50L), eq(date))).thenReturn(new HistoryResponse(
                50L, date, List.of(new HistoryResponse.HistoryEntry(1L, "Alice", 6, true))));

        mockMvc.perform(get("/api/groups/50/history").param("date", "2024-06-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2024-06-10"))
                .andExpect(jsonPath("$.members[0].count").value(6));
    }

    @Test
    void historyWithUnparseableDateReturns400() throws Exception {
        mockMvc.perform(get("/api/groups/50/history").param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
    }
}
