package com.dsatracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "group_target_votes", uniqueConstraints =
        @UniqueConstraint(name = "uq_group_target_vote", columnNames = {"group_id", "user_id", "poll_version"}))
public class GroupTargetVote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "poll_version", nullable = false)
    private int pollVersion;

    @Column(name = "proposed_target", nullable = false)
    private int proposedTarget;

    @Column(name = "voted_at", nullable = false)
    private Instant votedAt;

    public Long getId() { return id; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public int getPollVersion() { return pollVersion; }
    public void setPollVersion(int pollVersion) { this.pollVersion = pollVersion; }
    public int getProposedTarget() { return proposedTarget; }
    public void setProposedTarget(int proposedTarget) { this.proposedTarget = proposedTarget; }
    public Instant getVotedAt() { return votedAt; }
    public void setVotedAt(Instant votedAt) { this.votedAt = votedAt; }
}