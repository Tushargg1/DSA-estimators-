package com.dsatracker.dto;

import java.time.LocalDate;
import java.util.List;

public record GroupActivityResponse(
        Long groupId,
        Long userId,
        String period,
        LocalDate from,
        LocalDate to,
        LocalDate joinedOn,
        int target,
        String targetMode,
        List<Day> days
) {
    public record Day(LocalDate date, int count, String status) {
    }
}