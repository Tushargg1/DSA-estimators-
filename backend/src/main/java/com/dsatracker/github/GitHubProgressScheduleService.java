package com.dsatracker.github;

import com.dsatracker.util.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Service
public class GitHubProgressScheduleService {
    public static final String TIMEZONE = "Asia/Kolkata";
    static final LocalTime DEFAULT_FIRST_TIME = LocalTime.of(9, 0);
    static final LocalTime DEFAULT_SECOND_TIME = LocalTime.of(21, 0);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final GitHubProgressPushScheduleRepository schedules;
    private final Clock clock;

    @Autowired
    public GitHubProgressScheduleService(GitHubProgressPushScheduleRepository schedules) {
        this(schedules, Clock.systemUTC());
    }

    GitHubProgressScheduleService(GitHubProgressPushScheduleRepository schedules,
                                  Clock clock) {
        this.schedules = schedules;
        this.clock = clock;
    }

    @Transactional
    public GitHubDtos.ProgressScheduleResponse get(Long userId) {
        Instant now = clock.instant();
        return response(ensureAndLock(userId, now));
    }

    @Transactional
    public GitHubDtos.ProgressScheduleResponse update(
            Long userId, GitHubDtos.ProgressScheduleUpdateRequest request) {
        if (request == null || request.enabled() == null) {
            throw invalid("enabled is required");
        }
        LocalTime first = parseTime(request.firstTime(), "firstTime");
        LocalTime second = parseTime(request.secondTime(), "secondTime");
        if (first.equals(second)) throw invalid("Schedule times must be distinct");
        if (first.isAfter(second)) {
            LocalTime swap = first;
            first = second;
            second = swap;
        }

        Instant now = clock.instant();
        GitHubProgressPushSchedule schedule = ensureAndLock(userId, now);
        Instant next = request.enabled() ? nextOccurrence(now, first, second) : null;
        schedule.update(request.enabled(), first, second, next, now);
        return response(schedules.save(schedule));
    }

    @Transactional
    public boolean consumeDue(Long userId) {
        Instant now = clock.instant();
        GitHubProgressPushSchedule schedule = ensureAndLock(userId, now);
        if (!schedule.isEnabled() || schedule.getNextRunAt() == null
                || schedule.getNextRunAt().isAfter(now)) {
            return false;
        }
        schedule.advanceTo(nextOccurrence(now, schedule.getFirstTime(),
                schedule.getSecondTime()), now);
        schedules.save(schedule);
        return true;
    }

    static Instant nextOccurrence(Instant after, LocalTime first, LocalTime second) {
        ZonedDateTime localNow = after.atZone(TimeUtil.IST);
        LocalDate date = localNow.toLocalDate();
        Instant firstToday = date.atTime(first).atZone(TimeUtil.IST).toInstant();
        if (firstToday.isAfter(after)) return firstToday;
        Instant secondToday = date.atTime(second).atZone(TimeUtil.IST).toInstant();
        if (secondToday.isAfter(after)) return secondToday;
        return date.plusDays(1).atTime(first).atZone(TimeUtil.IST).toInstant();
    }

    private GitHubProgressPushSchedule ensureAndLock(Long userId, Instant now) {
        schedules.insertDefaults(userId,
                nextOccurrence(now, DEFAULT_FIRST_TIME, DEFAULT_SECOND_TIME));
        return schedules.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Progress push schedule could not be created"));
    }

    private static LocalTime parseTime(String value, String field) {
        if (value == null || !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
            throw invalid(field + " must use HH:mm at minute precision");
        }
        try {
            return LocalTime.parse(value, TIME_FORMAT);
        } catch (DateTimeParseException ex) {
            throw invalid(field + " must be a valid HH:mm time");
        }
    }

    private static GitHubDtos.ProgressScheduleResponse response(
            GitHubProgressPushSchedule schedule) {
        return new GitHubDtos.ProgressScheduleResponse(
                schedule.isEnabled(), TIME_FORMAT.format(schedule.getFirstTime()),
                TIME_FORMAT.format(schedule.getSecondTime()), schedule.getTimezone(),
                schedule.getNextRunAt());
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
