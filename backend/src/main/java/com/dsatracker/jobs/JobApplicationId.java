package com.dsatracker.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class JobApplicationId implements Serializable {
    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    public JobApplicationId() { }

    public JobApplicationId(Long listingId, Long userId) {
        this.listingId = listingId;
        this.userId = userId;
    }

    public Long getListingId() { return listingId; }
    public void setListingId(Long listingId) { this.listingId = listingId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof JobApplicationId other)) return false;
        return Objects.equals(listingId, other.listingId)
                && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(listingId, userId);
    }
}
