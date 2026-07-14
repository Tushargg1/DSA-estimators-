package com.dsatracker.service;

import com.dsatracker.dto.CreateGroupRequest;
import com.dsatracker.dto.GroupResponse;
import com.dsatracker.dto.HistoryResponse;
import com.dsatracker.dto.JoinGroupRequest;
import com.dsatracker.dto.LeaderboardResponse;
import com.dsatracker.model.DailyCount;
import com.dsatracker.model.DailyCountId;
import com.dsatracker.model.Group;
import com.dsatracker.model.GroupMember;
import com.dsatracker.model.GroupMemberId;
import com.dsatracker.model.User;
import com.dsatracker.repository.DailyCountRepository;
import com.dsatracker.repository.GroupMemberRepository;
import com.dsatracker.repository.GroupRepository;
import com.dsatracker.repository.SubmissionRepository;
import com.dsatracker.repository.UserRepository;
import com.dsatracker.util.TimeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GroupService} (tasks 8.4&ndash;8.6). Database-free: all
 * repositories and {@link StreakService} are Mockito mocks and "today in IST" is
 * pinned via a fixed {@link Clock}.
 */
@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    /** 2024-06-15T06:00:00Z -> 2024-06-15 11:30 IST (mid-day, no boundary ambiguity). */
    private static final Instant NOW = Instant.parse("2024-06-15T06:00:00Z");
    private static final LocalDate TODAY = TimeUtil.toIstDate(NOW);
    private static final String INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private DailyCountRepository dailyCountRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private StreakService streakService;

    private GroupService newService() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        return new GroupService(groupRepository, groupMemberRepository, userRepository,
                dailyCountRepository, submissionRepository, streakService, fixed);
    }

    private User user(long id, String name, int dailyTarget) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setDailyTarget(dailyTarget);
        return u;
    }

    // ------------------------------------------------------------------
    // Task 8.4: create.
    // ------------------------------------------------------------------

    @Test
    void createGroupGeneratesUniqueCodeAndEnrollsCreatorAsMember() {
        when(userRepository.existsById(7L)).thenReturn(true);
        when(groupRepository.findByInviteCode(anyString())).thenReturn(Optional.empty());
        when(groupRepository.save(any(Group.class))).thenAnswer(inv -> {
            Group g = inv.getArgument(0);
            g.setId(100L);
            return g;
        });
        when(groupMemberRepository.existsById(any(GroupMemberId.class))).thenReturn(false);

        GroupResponse response = newService().createGroup(new CreateGroupRequest("Friends", 7L));

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.name()).isEqualTo("Friends");
        assertThat(response.createdBy()).isEqualTo(7L);
        // Unique, unambiguous invite code of the configured length.
        assertThat(response.inviteCode()).hasSize(6);
        assertThat(response.inviteCode()).matches("[" + INVITE_ALPHABET + "]+");

        // Creator is persisted as a member of the new group.
        ArgumentCaptor<GroupMember> memberCaptor = ArgumentCaptor.forClass(GroupMember.class);
        verify(groupMemberRepository).save(memberCaptor.capture());
        GroupMemberId savedId = memberCaptor.getValue().getId();
        assertThat(savedId.getGroupId()).isEqualTo(100L);
        assertThat(savedId.getUserId()).isEqualTo(7L);
    }

    @Test
    void createGroupRegeneratesCodeOnCollision() {
        when(userRepository.existsById(7L)).thenReturn(true);
        // First generated code collides, second is free.
        when(groupRepository.findByInviteCode(anyString()))
                .thenReturn(Optional.of(new Group()))
                .thenReturn(Optional.empty());
        when(groupRepository.save(any(Group.class))).thenAnswer(inv -> {
            Group g = inv.getArgument(0);
            g.setId(101L);
            return g;
        });
        when(groupMemberRepository.existsById(any(GroupMemberId.class))).thenReturn(false);

        GroupResponse response = newService().createGroup(new CreateGroupRequest("Team", 7L));

        assertThat(response.inviteCode()).hasSize(6);
        verify(groupRepository).save(any(Group.class));
    }

    @Test
    void createGroupWithMissingCreatorReturns404() {
        when(userRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> newService().createGroup(new CreateGroupRequest("X", 999L)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(groupRepository, never()).save(any());
    }

    @Test
    void createGroupWithMissingFieldsReturns400() {
        assertThatThrownBy(() -> newService().createGroup(new CreateGroupRequest(null, 1L)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    // ------------------------------------------------------------------
    // Task 8.4: join.
    // ------------------------------------------------------------------

    @Test
    void joinWithValidCodeAddsMember() {
        Group group = new Group();
        group.setId(50L);
        group.setName("Squad");
        group.setInviteCode("ABC234");
        group.setCreatedBy(1L);
        when(userRepository.existsById(9L)).thenReturn(true);
        when(groupRepository.findByInviteCode("ABC234")).thenReturn(Optional.of(group));
        when(groupMemberRepository.existsById(any(GroupMemberId.class))).thenReturn(false);

        GroupResponse response = newService().joinGroup(new JoinGroupRequest("ABC234", 9L));

        assertThat(response.id()).isEqualTo(50L);
        ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
        verify(groupMemberRepository).save(captor.capture());
        assertThat(captor.getValue().getId().getGroupId()).isEqualTo(50L);
        assertThat(captor.getValue().getId().getUserId()).isEqualTo(9L);
    }

    @Test
    void joinWithInvalidCodeReturns404() {
        when(userRepository.existsById(9L)).thenReturn(true);
        when(groupRepository.findByInviteCode("NOPE99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().joinGroup(new JoinGroupRequest("NOPE99", 9L)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(groupMemberRepository, never()).save(any());
    }

    @Test
    void joiningTwiceIsIdempotent() {
        Group group = new Group();
        group.setId(50L);
        group.setName("Squad");
        group.setInviteCode("ABC234");
        group.setCreatedBy(1L);
        when(userRepository.existsById(9L)).thenReturn(true);
        when(groupRepository.findByInviteCode("ABC234")).thenReturn(Optional.of(group));
        // User is already a member.
        when(groupMemberRepository.existsById(any(GroupMemberId.class))).thenReturn(true);

        GroupResponse response = newService().joinGroup(new JoinGroupRequest("ABC234", 9L));

        assertThat(response.id()).isEqualTo(50L);
        // No duplicate row inserted.
        verify(groupMemberRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Task 8.5: leaderboard.
    // ------------------------------------------------------------------

    @Test
    void leaderboardAggregatesPerMemberAndSortsByTodayCountThenTotal() {
        Group group = new Group();
        group.setId(50L);
        group.setName("Squad");
        when(groupRepository.findById(50L)).thenReturn(Optional.of(group));

        User alice = user(1L, "Alice", 5);
        User bob = user(2L, "Bob", 3);
        when(groupMemberRepository.findByIdGroupId(50L)).thenReturn(List.of(
                new GroupMember(new GroupMemberId(50L, 1L), NOW),
                new GroupMember(new GroupMemberId(50L, 2L), NOW)));
        when(userRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(alice, bob));

        // Alice: 2 today; Bob: 4 today -> Bob should sort first.
        when(dailyCountRepository.findByIdUserIdAndIdDateIst(1L, TODAY))
                .thenReturn(Optional.of(new DailyCount(new DailyCountId(1L, TODAY), 2, false)));
        when(dailyCountRepository.findByIdUserIdAndIdDateIst(2L, TODAY))
                .thenReturn(Optional.of(new DailyCount(new DailyCountId(2L, TODAY), 4, true)));

        when(streakService.getStreaks(1L)).thenReturn(new StreakService.StreakInfo(3, 10));
        when(streakService.getStreaks(2L)).thenReturn(new StreakService.StreakInfo(1, 4));
        when(submissionRepository.countFirstAttemptsByUserId(1L)).thenReturn(120L);
        when(submissionRepository.countFirstAttemptsByUserId(2L)).thenReturn(55L);

        LeaderboardResponse response = newService().getLeaderboard(50L);

        assertThat(response.groupId()).isEqualTo(50L);
        assertThat(response.groupName()).isEqualTo("Squad");
        assertThat(response.members()).hasSize(2);

        // Bob first (4 > 2 today).
        LeaderboardResponse.MemberEntry first = response.members().get(0);
        assertThat(first.userId()).isEqualTo(2L);
        assertThat(first.userName()).isEqualTo("Bob");
        assertThat(first.todayCount()).isEqualTo(4);
        assertThat(first.dailyTarget()).isEqualTo(3);
        assertThat(first.currentStreak()).isEqualTo(1);
        assertThat(first.longestStreak()).isEqualTo(4);
        assertThat(first.totalSolved()).isEqualTo(55L);

        LeaderboardResponse.MemberEntry second = response.members().get(1);
        assertThat(second.userId()).isEqualTo(1L);
        assertThat(second.todayCount()).isEqualTo(2);
        assertThat(second.totalSolved()).isEqualTo(120L);
    }

    @Test
    void leaderboardZeroFillsMembersWithNoTodayRow() {
        Group group = new Group();
        group.setId(50L);
        group.setName("Squad");
        when(groupRepository.findById(50L)).thenReturn(Optional.of(group));

        User alice = user(1L, "Alice", 5);
        when(groupMemberRepository.findByIdGroupId(50L)).thenReturn(List.of(
                new GroupMember(new GroupMemberId(50L, 1L), NOW)));
        when(userRepository.findAllById(List.of(1L))).thenReturn(List.of(alice));
        when(dailyCountRepository.findByIdUserIdAndIdDateIst(1L, TODAY)).thenReturn(Optional.empty());
        when(streakService.getStreaks(1L)).thenReturn(new StreakService.StreakInfo(0, 0));
        when(submissionRepository.countFirstAttemptsByUserId(1L)).thenReturn(0L);

        LeaderboardResponse response = newService().getLeaderboard(50L);

        assertThat(response.members()).hasSize(1);
        assertThat(response.members().get(0).todayCount()).isZero();
    }

    @Test
    void leaderboardForMissingGroupReturns404() {
        when(groupRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().getLeaderboard(404L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // ------------------------------------------------------------------
    // Task 8.6: history.
    // ------------------------------------------------------------------

    @Test
    void historyReturnsPerMemberCountsWithZeroFillForMissingRows() {
        LocalDate date = LocalDate.of(2024, 6, 10);
        Group group = new Group();
        group.setId(50L);
        group.setName("Squad");
        when(groupRepository.findById(50L)).thenReturn(Optional.of(group));

        User alice = user(1L, "Alice", 5);
        User bob = user(2L, "Bob", 5);
        when(groupMemberRepository.findByIdGroupId(50L)).thenReturn(List.of(
                new GroupMember(new GroupMemberId(50L, 1L), NOW),
                new GroupMember(new GroupMemberId(50L, 2L), NOW)));
        when(userRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(alice, bob));

        // Alice has a row for the date; Bob has none (zero-filled).
        when(dailyCountRepository.findByIdUserIdAndIdDateIst(1L, date))
                .thenReturn(Optional.of(new DailyCount(new DailyCountId(1L, date), 6, true)));
        when(dailyCountRepository.findByIdUserIdAndIdDateIst(2L, date))
                .thenReturn(Optional.empty());

        HistoryResponse response = newService().getHistory(50L, date);

        assertThat(response.groupId()).isEqualTo(50L);
        assertThat(response.date()).isEqualTo(date);
        assertThat(response.members()).hasSize(2);

        HistoryResponse.HistoryEntry aliceEntry = response.members().get(0);
        assertThat(aliceEntry.userId()).isEqualTo(1L);
        assertThat(aliceEntry.count()).isEqualTo(6);
        assertThat(aliceEntry.targetHit()).isTrue();

        HistoryResponse.HistoryEntry bobEntry = response.members().get(1);
        assertThat(bobEntry.userId()).isEqualTo(2L);
        assertThat(bobEntry.count()).isZero();
        assertThat(bobEntry.targetHit()).isFalse();
    }

    @Test
    void historyForMissingGroupReturns404() {
        when(groupRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().getHistory(404L, LocalDate.of(2024, 6, 10)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
