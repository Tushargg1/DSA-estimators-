package com.dsatracker.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "job_listings")
public class JobListing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String company;

    @Column(name = "job_url", nullable = false, length = 2048)
    private String jobUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "posted_by", nullable = false)
    private Long postedBy;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "experience_required")
    private Integer experienceRequired;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getJobUrl() { return jobUrl; }
    public void setJobUrl(String jobUrl) { this.jobUrl = jobUrl; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getPostedBy() { return postedBy; }
    public void setPostedBy(Long postedBy) { this.postedBy = postedBy; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Integer getExperienceRequired() { return experienceRequired; }
    public void setExperienceRequired(Integer experienceRequired) { this.experienceRequired = experienceRequired; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
