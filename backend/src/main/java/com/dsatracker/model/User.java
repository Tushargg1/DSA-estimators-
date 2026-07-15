package com.dsatracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A tracked user and their linked platform usernames.
 *
 * <p>Maps to the {@code users} table (design.md "Database Schema"). The
 * MySQL {@code BIGINT AUTO_INCREMENT} identity column is generated via
 * {@link GenerationType#IDENTITY}. Timestamps are {@link Instant} (UTC); the
 * Hibernate {@code jdbc.time_zone=UTC} setting keeps stored values in UTC.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "email", nullable = false, unique = true, length = 150)
    private String email;

    @Column(name = "leetcode_username", length = 100)
    private String leetcodeUsername;

    @Column(name = "codeforces_username", length = 100)
    private String codeforcesUsername;

    @Column(name = "gfg_username", length = 100)
    private String gfgUsername;

    @Column(name = "daily_target", nullable = false)
    private int dailyTarget = 5;

    @Column(name = "onboarding_complete", nullable = false)
    private boolean onboardingComplete = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getLeetcodeUsername() {
        return leetcodeUsername;
    }

    public void setLeetcodeUsername(String leetcodeUsername) {
        this.leetcodeUsername = leetcodeUsername;
    }

    public String getCodeforcesUsername() {
        return codeforcesUsername;
    }

    public void setCodeforcesUsername(String codeforcesUsername) {
        this.codeforcesUsername = codeforcesUsername;
    }

    public String getGfgUsername() {
        return gfgUsername;
    }

    public void setGfgUsername(String gfgUsername) {
        this.gfgUsername = gfgUsername;
    }

    public int getDailyTarget() {
        return dailyTarget;
    }

    public void setDailyTarget(int dailyTarget) {
        this.dailyTarget = dailyTarget;
    }

    public boolean isOnboardingComplete() {
        return onboardingComplete;
    }

    public void setOnboardingComplete(boolean onboardingComplete) {
        this.onboardingComplete = onboardingComplete;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
