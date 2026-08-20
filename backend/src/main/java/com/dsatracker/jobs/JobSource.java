package com.dsatracker.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "job_sources")
public class JobSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "added_by", nullable = false)
    private Long addedBy;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 200)
    private String label;

    @Column(name = "last_scraped_at")
    private Instant lastScrapedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /** Resumable sweep position: index of the next record to fetch from the portal. */
    @Column(name = "sync_cursor", nullable = false)
    private int syncCursor;

    /** When the most recent full sweep finished (cursor wrapped back to 0). */
    @Column(name = "sweep_completed_at")
    private Instant sweepCompletedAt;

    /** Which adapter handles this source, e.g. "accenture". Null means generic HTML scrape. */
    @Column(length = 40)
    private String adapter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAddedBy() { return addedBy; }
    public void setAddedBy(Long addedBy) { this.addedBy = addedBy; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Instant getLastScrapedAt() { return lastScrapedAt; }
    public void setLastScrapedAt(Instant lastScrapedAt) { this.lastScrapedAt = lastScrapedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public int getSyncCursor() { return syncCursor; }
    public void setSyncCursor(int syncCursor) { this.syncCursor = syncCursor; }
    public Instant getSweepCompletedAt() { return sweepCompletedAt; }
    public void setSweepCompletedAt(Instant sweepCompletedAt) { this.sweepCompletedAt = sweepCompletedAt; }
    public String getAdapter() { return adapter; }
    public void setAdapter(String adapter) { this.adapter = adapter; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
