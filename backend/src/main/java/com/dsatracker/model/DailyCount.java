package com.dsatracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Denormalized per-user, per-day rollup of counted submissions.
 *
 * <p>Maps to the {@code daily_counts} table with the composite primary key
 * {@code (user_id, date_ist)} via a {@link DailyCountId} {@code @EmbeddedId}.
 * This table is updated whenever a {@code counted_for_target = true} submission
 * lands, avoiding recomputing streaks from raw submissions on each page load
 * (design.md).
 */
@Entity
@Table(name = "daily_counts")
public class DailyCount {

    @EmbeddedId
    private DailyCountId id;

    @Column(name = "count", nullable = false)
    private int count = 0;

    @Column(name = "target_hit", nullable = false)
    private boolean targetHit = false;

    public DailyCount() {
    }

    public DailyCount(DailyCountId id, int count, boolean targetHit) {
        this.id = id;
        this.count = count;
        this.targetHit = targetHit;
    }

    public DailyCountId getId() {
        return id;
    }

    public void setId(DailyCountId id) {
        this.id = id;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public boolean isTargetHit() {
        return targetHit;
    }

    public void setTargetHit(boolean targetHit) {
        this.targetHit = targetHit;
    }
}
