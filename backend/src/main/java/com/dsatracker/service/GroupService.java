package com.dsatracker.service;

import com.dsatracker.dto.CreateGroupRequest;
import com.dsatracker.dto.GroupResponse;
import com.dsatracker.dto.HistoryResponse;
import com.dsatracker.dto.JoinGroupRequest;
import com.dsatracker.dto.LeaderboardResponse;
import com.dsatracker.model.DailyCount;
import com.dsatracker.model.Group;
import com.dsatracker.model.GroupMember;
import com.dsatracker.model.User;
import com.dsatracker.repository.DailyCountRepository;
import com.dsatracker.repository.GroupMemberRepository;
import com.dsatracker.repository.GroupRepository;
import com.dsatracker.repository.SubmissionRepository;
import com.dsatracker.repository.UserRepository;
import com.dsatracker.util.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the group create/join flows and the leaderboard / history read models
 * (tasks 8.4&ndash;8.6, Requirements 6.1&ndash;6.4 and 4.5).
 *
 * <p>The {@link GroupController} stays thin and delegates all logic here:
 * invite-code generation, membership management, and the joins across
 * {@code users}, {@code daily_counts}, {@link StreakService}, and submission
 * totals that make up the leaderboard.
 *
 * <p>Not-found conditions surface as {@link ResponseStatusException} with
 * {@link HttpStatus#NOT_FOUND} so the controller layer needs no extra mapping
 * (and no shared {@code @ControllerAdvice} is introduced).
 *
 * <p>"Today" in IST is resolved from an injectable {@link Clock} (defaulting to
 * {@link Clock#systemUTC()}), mirroring {@link StreakService}, so the leaderboard
 * "today count" boundary is deterministic under test. The instant is converted to
 * an IST calendar date via {@link TimeUtil#toIstDate(Instant)}.
 */
@Service
public class GroupService {

    /**
     * Invite-code alphabet: uppercase letters and digits with the visually
     * ambiguous {@code 0/O/1/I} removed, so codes are easy to read out and type.
     */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** Generated invite-code length; well within the schema's {@code VARCHAR(20)}. */
    private static final int CODE_LENGTH = 6;

    /**
     * Bounded attempts to find a collision-free code at {@link #CODE_LENGTH}
     * before widening the code by one character to keep the space large.
     */
    private static final int MAX_CODE_ATTEMPTS = 10;

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final DailyCountRepository dailyCountRepository;
    private final SubmissionRepository submissionRepository;
    private final StreakService streakService;
    private final GroupTargetService groupTargetService;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public GroupService(GroupRepository groupRepository,
                        GroupMemberRepository groupMemberRepository,
                        UserRepository userRepository,
                        DailyCountRepository dailyCountRepository,
                        SubmissionRepository submissionRepository,
                        StreakService streakService,
                        GroupTargetService groupTargetService) {
        this(groupRepository, groupMemberRepository, userRepository, dailyCountRepository,
                submissionRepository, streakService, groupTargetService, Clock.systemUTC());
    }

    GroupService(GroupRepository groupRepository,
                 GroupMemberRepository groupMemberRepository,
                 UserRepository userRepository,
                 DailyCountRepository dailyCountRepository,
                 SubmissionRepository submissionRepository,
                 StreakService streakService,
                 Clock clock) {
        this(groupRepository, groupMemberRepository, userRepository, dailyCountRepository,
                submissionRepository, streakService, null, clock);
    }

    GroupService(GroupRepository groupRepository,
                 GroupMemberRepository groupMemberRepository,
                 UserRepository userRepository,
                 DailyCountRepository dailyCountRepository,
                 SubmissionRepository submissionRepository,
                 StreakService streakService,
                 GroupTargetService groupTargetService,
                 Clock clock) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
        this.dailyCountRepository = dailyCountRepository;
        this.submissionRepository = submissionRepository;
        this.streakService = streakService;
        this.groupTargetService = groupTargetService;
        this.clock = clock;
    }

    /**
     * Creates a group with a unique invite code and enrolls the creator as its
     * first member (task 8.4, Requirement 6.1).
     *
     * @param request the create payload (name + creator user id)
     * @return the created group
     * @throws ResponseStatusException 400 if the payload is missing required
     *                                 fields, 404 if the creator does not exist
     */
    @Transactional
    public GroupResponse createGroup(CreateGroupRequest request, Long creatorId) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name is required");
        }
        if (!userRepository.existsById(creatorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "User " + creatorId + " not found");
        }

        Instant now = clock.instant();
        Group group = new Group();
        group.setName(request.name());
        group.setInviteCode(generateUniqueInviteCode());
        group.setCreatedBy(creatorId);
        group.setCreatedAt(now);
        Group saved = groupRepository.save(group);

        // Enroll the creator as the first member (idempotent by construction).
        addMember(saved.getId(), creatorId, now);

        return GroupResponse.from(saved);
    }

    /**
     * Adds a user to a group identified by invite code (task 8.4, Requirements
     * 6.2, 6.3). Joining is idempotent: a repeat join for an existing member is a
     * no-op rather than an error or duplicate row. A user may belong to any number
     * of groups.
     *
     * @param request the join payload (invite code + user id)
     * @return the joined group
     * @throws ResponseStatusException 400 if the payload is missing required
     *                                 fields, 404 if the invite code is invalid or
     *                                 the user does not exist
     */
    @Transactional
    public GroupResponse joinGroup(JoinGroupRequest request, Long userId) {
        if (request == null || request.inviteCode() == null || request.inviteCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "inviteCode is required");
        }
        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "User " + userId + " not found");
        }

        Group group = groupRepository.findByInviteCode(request.inviteCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Invalid invite code"));

        addMember(group.getId(), userId, clock.instant());
        return GroupResponse.from(group);
    }

    /** Lists groups for the authenticated user without exposing unrelated groups. */
    public List<GroupResponse> getGroupsForUser(Long userId) {
        List<Long> ids = groupMemberRepository.findByIdUserId(userId).stream()
                .map(membership -> membership.getId().getGroupId())
                .toList();
        Map<Long, Group> byId = new LinkedHashMap<>();
        groupRepository.findAllById(ids).forEach(group -> byId.put(group.getId(), group));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .map(GroupResponse::from).toList();
    }

    /**
     * Builds the current leaderboard for a group (task 8.5, Requirement 6.4):
     * per-member today's count / target, current + longest streak, and all-time
     * problems solved (first-attempts only). Members are sorted by
     * {@code todayCount} descending, then {@code totalSolved} descending.
     *
     * @param groupId the group id
     * @return the leaderboard response
     * @throws ResponseStatusException 404 if the group does not exist
     */
    public LeaderboardResponse getLeaderboard(Long groupId) {
        Group group = requireGroup(groupId);
        LocalDate today = TimeUtil.toIstDate(clock.instant());

        List<User> members = membersOf(groupId);
        int groupTarget = groupTargetService == null
                ? group.getDailyTarget() : groupTargetService.currentTarget(groupId);
        List<LeaderboardResponse.MemberEntry> entries = new ArrayList<>(members.size());
        for (User user : members) {
            int todayCount = dailyCountRepository
                    .findByIdUserIdAndIdDateIst(user.getId(), today)
                    .map(DailyCount::getCount)
                    .orElse(0);
            StreakService.StreakInfo streaks = streakService.getStreaks(user.getId());
            // Requirement 6.4: total problems solved counts first-attempts across
            // all history (including backfill), not just target-counted rows.
            long totalSolved = submissionRepository.countFirstAttemptsByUserId(user.getId());
            entries.add(new LeaderboardResponse.MemberEntry(
                    user.getId(),
                    user.getName(),
                    todayCount,
                    groupTargetService == null ? user.getDailyTarget() : groupTarget,
                    streaks.current(),
                    streaks.longest(),
                    totalSolved));
        }

        entries.sort(Comparator
                .comparingInt(LeaderboardResponse.MemberEntry::todayCount).reversed()
                .thenComparing(Comparator.comparingLong(LeaderboardResponse.MemberEntry::totalSolved).reversed()));

        return new LeaderboardResponse(group.getId(), group.getName(), entries);
    }

    /**
     * Returns each member's counted total for a specific IST calendar date (task
     * 8.6, Requirement 4.5). Members without a {@code daily_counts} row for the
     * date are zero-filled.
     *
     * @param groupId the group id
     * @param date    the IST calendar date to report
     * @return the history response
     * @throws ResponseStatusException 404 if the group does not exist
     */
    public HistoryResponse getHistory(Long groupId, LocalDate date) {
        Group group = requireGroup(groupId);

        List<GroupMember> memberships = groupMemberRepository.findByIdGroupId(groupId);
        Map<Long, LocalDate> joinedByUser = new LinkedHashMap<>();
        for (GroupMember membership : memberships) {
            joinedByUser.put(membership.getId().getUserId(),
                    TimeUtil.toIstDate(membership.getJoinedAt()));
        }
        int groupTarget = groupTargetService == null
                ? group.getDailyTarget() : groupTargetService.currentTarget(groupId);
        List<User> members = membersOf(groupId);
        List<HistoryResponse.HistoryEntry> entries = new ArrayList<>(members.size());
        for (User user : members) {
            LocalDate joinedOn = joinedByUser.get(user.getId());
            if (joinedOn != null && date.isBefore(joinedOn)) continue;
            Optional<DailyCount> row = dailyCountRepository
                    .findByIdUserIdAndIdDateIst(user.getId(), date);
            int count = row.map(DailyCount::getCount).orElse(0);
            boolean targetHit = groupTargetService == null
                    ? row.map(DailyCount::isTargetHit).orElse(false)
                    : count >= groupTarget;
            entries.add(new HistoryResponse.HistoryEntry(
                    user.getId(), user.getName(), count, targetHit));
        }

        return new HistoryResponse(group.getId(), date, entries);
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private Group requireGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Group " + groupId + " not found"));
    }

    /**
     * Resolves a group's members into {@link User} rows, preserving membership
     * order. Memberships referencing a now-missing user are skipped defensively.
     */
    private List<User> membersOf(Long groupId) {
        List<GroupMember> memberships = groupMemberRepository.findByIdGroupId(groupId);
        List<Long> userIds = new ArrayList<>(memberships.size());
        for (GroupMember m : memberships) {
            userIds.add(m.getId().getUserId());
        }

        Map<Long, User> byId = new LinkedHashMap<>();
        for (User u : userRepository.findAllById(userIds)) {
            byId.put(u.getId(), u);
        }

        List<User> users = new ArrayList<>(userIds.size());
        for (Long id : userIds) {
            User u = byId.get(id);
            if (u != null) {
                users.add(u);
            }
        }
        return users;
    }

    /**
     * Idempotently persists a membership with one conflict-safe database write.
     * The database ignores only the expected composite-key conflict; unrelated
     * persistence failures still propagate and roll back the transaction.
     */
    private void addMember(Long groupId, Long userId, Instant joinedAt) {
        groupMemberRepository.insertIfAbsent(groupId, userId, joinedAt);
    }

    /**
     * Generates an invite code that does not collide with an existing one. Tries a
     * bounded number of {@link #CODE_LENGTH}-char codes, then widens the length by
     * one per subsequent attempt to virtually guarantee a free code even under an
     * (implausible) high collision rate.
     */
    private String generateUniqueInviteCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = randomCode(CODE_LENGTH);
            if (groupRepository.findByInviteCode(code).isEmpty()) {
                return code;
            }
        }
        // Extremely unlikely fallback: widen the code until a free one is found.
        int length = CODE_LENGTH + 1;
        while (length <= 20) {
            String code = randomCode(length);
            if (groupRepository.findByInviteCode(code).isEmpty()) {
                return code;
            }
            length++;
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unable to generate a unique invite code");
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
