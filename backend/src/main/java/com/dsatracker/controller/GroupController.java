package com.dsatracker.controller;

import com.dsatracker.dto.CreateGroupRequest;
import com.dsatracker.dto.GroupResponse;
import com.dsatracker.dto.HistoryResponse;
import com.dsatracker.dto.JoinGroupRequest;
import com.dsatracker.dto.LeaderboardResponse;
import com.dsatracker.service.GroupService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST controller for group management and the group leaderboard/history read
 * models (tasks 8.4&ndash;8.6, Requirements 6.1&ndash;6.4 and 4.5).
 *
 * <p>Thin by design: every handler delegates to {@link GroupService}. Not-found
 * and bad-request conditions are raised as
 * {@link org.springframework.web.server.ResponseStatusException} inside the
 * service, so Spring maps them to the right HTTP status without a shared
 * {@code @ControllerAdvice}.
 *
 * <p><b>Security note:</b> like the rest of the current API these endpoints are
 * unauthenticated (no auth layer exists yet). Fine for a public side project,
 * but membership/creation should be protected before any production exposure.
 *
 * <p>CORS is configured globally (task 12.4); no per-controller CORS here.
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /**
     * Creates a group and returns its generated invite code (Requirement 6.1).
     *
     * @param request create payload (name + creator user id)
     * @return {@code 201 Created} with the created group
     */
    @PostMapping
    public ResponseEntity<GroupResponse> createGroup(@RequestBody CreateGroupRequest request) {
        GroupResponse response = groupService.createGroup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Joins a group via invite code (Requirements 6.2, 6.3). Idempotent for an
     * already-joined user.
     *
     * @param request join payload (invite code + user id)
     * @return {@code 200 OK} with the joined group
     */
    @PostMapping("/join")
    public ResponseEntity<GroupResponse> joinGroup(@RequestBody JoinGroupRequest request) {
        return ResponseEntity.ok(groupService.joinGroup(request));
    }

    /**
     * Returns the group's current leaderboard (Requirement 6.4).
     *
     * @param id the group id
     * @return {@code 200 OK} with the leaderboard
     */
    @GetMapping("/{id}/leaderboard")
    public ResponseEntity<LeaderboardResponse> leaderboard(@PathVariable Long id) {
        return ResponseEntity.ok(groupService.getLeaderboard(id));
    }

    /**
     * Returns each member's counted total for a specific IST calendar date
     * (Requirement 4.5). An unparseable {@code date} yields {@code 400 Bad
     * Request} (Spring's type conversion failure).
     *
     * @param id   the group id
     * @param date ISO {@code yyyy-MM-dd} IST calendar date
     * @return {@code 200 OK} with the per-member history for that date
     */
    @GetMapping("/{id}/history")
    public ResponseEntity<HistoryResponse> history(
            @PathVariable Long id,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(groupService.getHistory(id, date));
    }
}
