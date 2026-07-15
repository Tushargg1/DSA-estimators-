package com.dsatracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Composite primary key for {@link DailyCount}: {@code (user_id, date_ist)}.
 *
 * <p>{@code date_ist} is a calendar day in IST (see design.md time handling),
 * mapped to a {@link LocalDate} to match the SQL {@code DATE} column.
 */
@Embeddable
public class DailyCountId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "date_ist", nullable = false)
    private LocalDate dateIst;

    public DailyCountId() {
    }

    public DailyCountId(Long userId, LocalDate dateIst) {
        this.userId = userId;
        this.dateIst = dateIst;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDate getDateIst() {
        return dateIst;
    }

    public void setDateIst(LocalDate dateIst) {
        this.dateIst = dateIst;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DailyCountId that = (DailyCountId) o;
        return Objects.equals(userId, that.userId) && Objects.equals(dateIst, that.dateIst);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, dateIst);
    }
}
