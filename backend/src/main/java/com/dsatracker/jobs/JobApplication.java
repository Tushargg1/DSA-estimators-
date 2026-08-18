package com.dsatracker.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "job_applications")
public class JobApplication {
    @EmbeddedId
    private JobApplicationId id;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    public JobApplication() { }

    public JobApplication(JobApplicationId id, Instant appliedAt) {
        this.id = id;
        this.appliedAt = appliedAt;
    }

    public JobApplicationId getId() { return id; }
    public void setId(JobApplicationId id) { this.id = id; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
}
