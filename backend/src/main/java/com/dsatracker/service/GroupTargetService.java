package com.dsatracker.service;

import com.dsatracker.dto.GroupActivityResponse;
import com.dsatracker.dto.GroupTargetResponse;
import com.dsatracker.model.DailyCount;
import com.dsatracker.model.Group;
import com.dsatracker.model.GroupMember;
import com.dsatracker.model.GroupTargetVote;
import com.dsatracker.repository.DailyCountRepository;
import com.dsatracker.repository.GroupMemberRepository;
import com.dsatracker.repository.GroupRepository;
import com.dsatracker.repository.GroupTargetVoteRepository;
import com.dsatracker.util.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GroupTargetService {
    private static final int MINIMUM_TARGET = 3;
    private static final int AUTO_WINDOW_DAYS = 30;

    private final GroupRepository groups;
    private final GroupMemberRepository memberships;
    private final DailyCountRepository dailyCounts;
    private final GroupTargetVoteRepository votes;
    private final Clock clock;

    @Autowired
    public GroupTargetService(GroupRepository groups, GroupMemberRepository memberships,
                              DailyCountRepository dailyCounts, GroupTargetVoteRepository votes) {
        this(groups, memberships, dailyCounts, votes, Clock.systemUTC());
    }

    GroupTargetService(GroupRepository groups, GroupMemberRepository memberships,
                       DailyCountRepository dailyCounts, GroupTargetVoteRepository votes,
                       Clock clock) {
        this.groups = groups;
        this.memberships = memberships;
        this.dailyCounts = dailyCounts;
        this.votes = votes;
        this.clock = clock;
    }

    @Transactional
    public GroupTargetResponse getTarget(Long groupId, Long actorId) {
        Group group = lockedGroup(groupId);
        requireMember(groupId, actorId);
        refreshAutoTarget(group);
        return response(group, actorId);
    }

    @Transactional
    public GroupTargetResponse selectAuto(Long groupId, Long actorId) {
        Group group = lockedGroup(groupId);
        requireOwner(group, actorId);
        group.setTargetMode("AUTO");
        group.setPollActive(false);
        group.setDailyTarget(calculateAutoTarget(groupId));
        group.setTargetCalculatedForDate(today());
        groups.save(group);
        return response(group, actorId);
    }

    @Transactional
    public GroupTargetResponse startPoll(Long groupId, Long actorId) {
        Group group = lockedGroup(groupId);
        requireOwner(group, actorId);
        if (group.isPollActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A group target poll is already active");
        }
        group.setPollVersion(group.getPollVersion() + 1);
        group.setPollStartedAt(clock.instant());
        group.setPollActive(true);
        groups.save(group);
        return response(group, actorId);
    }

    @Transactional
    public GroupTargetResponse castVote(Long groupId, Long actorId, Integer proposedTarget) {
        if (proposedTarget == null || proposedTarget < MINIMUM_TARGET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Group target must be at least " + MINIMUM_TARGET);
        }
        Group group = lockedGroup(groupId);
        if (!group.isPollActive() || group.getPollStartedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No group target poll is active");
        }
        GroupMember member = requireMember(groupId, actorId);
        if (member.getJoinedAt().isAfter(group.getPollStartedAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You joined after this poll started and can vote in the next poll");
        }

        GroupTargetVote vote = votes
                .findByGroupIdAndUserIdAndPollVersion(groupId, actorId, group.getPollVersion())
                .orElseGet(GroupTargetVote::new);
        vote.setGroupId(groupId);
        vote.setUserId(actorId);
        vote.setPollVersion(group.getPollVersion());
        vote.setProposedTarget(proposedTarget);
        vote.setVotedAt(clock.instant());
        votes.save(vote);

        List<GroupTargetVote> currentVotes = pollVotes(group);
        int eligible = eligibleMembers(group);
        if (eligible > 0 && currentVotes.size() >= eligible) {
            int winningTarget = currentVotes.get(currentVotes.size() / 2).getProposedTarget();
            group.setDailyTarget(Math.max(MINIMUM_TARGET, winningTarget));
            group.setTargetMode("VOTE");
            group.setTargetCalculatedForDate(null);
            group.setPollActive(false);
            groups.save(group);
        }
        return response(group, actorId);
    }

    @Transactional
    public int currentTarget(Long groupId) {
        Group group = lockedGroup(groupId);
        refreshAutoTarget(group);
        return group.getDailyTarget();
    }

    @Transactional
    public GroupActivityResponse activity(Long groupId, Long userId, String requestedPeriod,
                                          LocalDate anchor) {
        Group group = lockedGroup(groupId);
        GroupMember member = requireMember(groupId, userId);
        refreshAutoTarget(group);

        String period;
        if (requestedPeriod == null || requestedPeriod.isBlank()
                || "MONTH".equalsIgnoreCase(requestedPeriod)) {
            period = "MONTH";
        } else if ("YEAR".equalsIgnoreCase(requestedPeriod)) {
            period = "YEAR";
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Period must be MONTH or YEAR");
        }
        LocalDate reference = anchor == null ? today() : anchor;
        LocalDate from = "YEAR".equals(period)
                ? reference.withDayOfYear(1) : reference.withDayOfMonth(1);
        LocalDate to = "YEAR".equals(period)
                ? reference.withDayOfYear(reference.lengthOfYear())
                : reference.withDayOfMonth(reference.lengthOfMonth());
        LocalDate joinedOn = TimeUtil.toIstDate(member.getJoinedAt());
        LocalDate today = today();

        Map<LocalDate, Integer> countByDate = new HashMap<>();
        for (DailyCount count : dailyCounts
                .findByIdUserIdAndIdDateIstBetweenOrderByIdDateIst(userId, from, to)) {
            countByDate.put(count.getId().getDateIst(), count.getCount());
        }

        List<GroupActivityResponse.Day> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int count = countByDate.getOrDefault(date, 0);
            String status;
            if (date.isBefore(joinedOn) || date.isAfter(today)) {
                status = "NOT_APPLICABLE";
            } else if (count >= group.getDailyTarget()) {
                status = "ACHIEVED";
            } else if (date.equals(today)) {
                status = "IN_PROGRESS";
            } else {
                status = "MISSED";
            }
            days.add(new GroupActivityResponse.Day(date, count, status));
        }
        return new GroupActivityResponse(groupId, userId, period, from, to, joinedOn,
                group.getDailyTarget(), group.getTargetMode(), days);
    }

    private void refreshAutoTarget(Group group) {
        LocalDate today = today();
        if ("AUTO".equals(group.getTargetMode())
                && !today.equals(group.getTargetCalculatedForDate())) {
            group.setDailyTarget(calculateAutoTarget(group.getId()));
            group.setTargetCalculatedForDate(today);
            groups.save(group);
        }
    }

    private int calculateAutoTarget(Long groupId) {
        LocalDate end = today().minusDays(1);
        LocalDate windowStart = end.minusDays(AUTO_WINDOW_DAYS - 1L);
        long solved = 0;
        long memberDays = 0;
        for (GroupMember member : memberships.findByIdGroupId(groupId)) {
            LocalDate joined = TimeUtil.toIstDate(member.getJoinedAt());
            LocalDate start = joined.isAfter(windowStart) ? joined : windowStart;
            if (start.isAfter(end)) continue;
            memberDays += ChronoUnit.DAYS.between(start, end) + 1;
            solved += dailyCounts
                    .findByIdUserIdAndIdDateIstBetweenOrderByIdDateIst(
                            member.getId().getUserId(), start, end)
                    .stream().mapToLong(DailyCount::getCount).sum();
        }
        if (memberDays == 0) return MINIMUM_TARGET;
        return Math.max(MINIMUM_TARGET, (int) (solved / memberDays) + 1);
    }

    private GroupTargetResponse response(Group group, Long actorId) {
        List<GroupTargetVote> currentVotes = group.isPollActive() ? pollVotes(group) : List.of();
        Integer currentVote = currentVotes.stream()
                .filter(vote -> vote.getUserId().equals(actorId))
                .map(GroupTargetVote::getProposedTarget)
                .findFirst().orElse(null);
        int eligible = group.isPollActive() ? eligibleMembers(group) : 0;
        return new GroupTargetResponse(
                group.getId(), group.getDailyTarget(), group.getTargetMode(),
                calculateAutoTarget(group.getId()), group.getCreatedBy().equals(actorId),
                new GroupTargetResponse.Poll(group.isPollActive(), group.getPollVersion(),
                        currentVotes.size(), eligible, currentVote));
    }

    private List<GroupTargetVote> pollVotes(Group group) {
        return votes.findByGroupIdAndPollVersionOrderByProposedTargetAsc(
                group.getId(), group.getPollVersion());
    }

    private int eligibleMembers(Group group) {
        if (group.getPollStartedAt() == null) return 0;
        return Math.toIntExact(memberships.countByIdGroupIdAndJoinedAtLessThanEqual(
                group.getId(), group.getPollStartedAt()));
    }

    private Group lockedGroup(Long groupId) {
        return groups.findByIdForUpdate(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Resource not found"));
    }

    private GroupMember requireMember(Long groupId, Long userId) {
        return memberships.findByIdGroupIdAndIdUserId(groupId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Resource not found"));
    }

    private void requireOwner(Group group, Long actorId) {
        requireMember(group.getId(), actorId);
        if (!group.getCreatedBy().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the group owner can change target mode");
        }
    }

    private LocalDate today() {
        return TimeUtil.toIstDate(clock.instant());
    }
}