package com.dsatracker.security;

import com.dsatracker.repository.GroupMemberRepository;
import com.dsatracker.repository.GroupRepository;
import com.dsatracker.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Centralizes principal parsing and non-disclosing membership authorization checks. */
@Service
public class AccessService {
    private final UserRepository users;
    private final GroupRepository groups;
    private final GroupMemberRepository memberships;

    public AccessService(UserRepository users, GroupRepository groups,
                         GroupMemberRepository memberships) {
        this.users = users;
        this.groups = groups;
        this.memberships = memberships;
    }

    public Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    public void requireVisibleUser(Long actorId, Long targetId) {
        if (!users.existsById(targetId)) {
            throw notFound();
        }
        if (!actorId.equals(targetId) && !memberships.shareGroup(actorId, targetId)) {
            throw notFound();
        }
    }

    public void requireSelf(Long actorId, Long targetId) {
        if (!actorId.equals(targetId) || !users.existsById(targetId)) {
            throw notFound();
        }
    }

    public void requireGroupMember(Long actorId, Long groupId) {
        if (!groups.existsById(groupId)
                || !memberships.existsByIdGroupIdAndIdUserId(groupId, actorId)) {
            throw notFound();
        }
    }

    public boolean isGroupMember(Long actorId, Long groupId) {
        return memberships.existsByIdGroupIdAndIdUserId(groupId, actorId);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
    }
}
