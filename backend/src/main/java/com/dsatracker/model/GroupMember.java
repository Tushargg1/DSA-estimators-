package com.dsatracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Join row linking a {@link User} to a {@link Group}.
 *
 * <p>Maps to the {@code group_members} table with the composite primary key
 * {@code (group_id, user_id)} modeled via an {@link GroupMemberId}
 * {@code @EmbeddedId} (design.md).
 */
@Entity
@Table(name = "group_members")
public class GroupMember {

    @EmbeddedId
    private GroupMemberId id;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    public GroupMember() {
    }

    public GroupMember(GroupMemberId id, Instant joinedAt) {
        this.id = id;
        this.joinedAt = joinedAt;
    }

    public GroupMemberId getId() {
        return id;
    }

    public void setId(GroupMemberId id) {
        this.id = id;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
}
