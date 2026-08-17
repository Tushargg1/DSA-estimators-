package com.dsatracker.github;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalTime;

@Entity
@Table(name = "github_progress_push_schedules")
public class GitHubProgressPushSchedule {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "enabled", nullable = false)
    private boolean enabled;
    @Column(name = "first_time", nullable = false)
    private LocalTime firstTime;
    @Column(name = "second_time", nullable = false)
    private LocalTime secondTime;
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;
    @Column(name = "next_run_at")
    private Instant nextRunAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GitHubProgressPushSchedule() { }

    public Long getUserId() { return userId; }
    public boolean isEnabled() { return enabled; }
    public LocalTime getFirstTime() { return firstTime; }
    public LocalTime getSecondTime() { return secondTime; }
    public String getTimezone() { return timezone; }
    public Instant getNextRunAt() { return nextRunAt; }

    public void update(boolean enabled, LocalTime firstTime, LocalTime secondTime,
                       Instant nextRunAt, Instant updatedAt) {
        this.enabled = enabled;
        this.firstTime = firstTime;
        this.secondTime = secondTime;
        this.timezone = GitHubProgressScheduleService.TIMEZONE;
        this.nextRunAt = nextRunAt;
        this.updatedAt = updatedAt;
    }

    public void advanceTo(Instant nextRunAt, Instant updatedAt) {
        this.nextRunAt = nextRunAt;
        this.updatedAt = updatedAt;
    }
}
