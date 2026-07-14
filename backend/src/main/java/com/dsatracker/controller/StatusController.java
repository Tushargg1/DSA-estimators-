package com.dsatracker.controller;

import com.dsatracker.dto.PollStatusResponse;
import com.dsatracker.model.Platform;
import com.dsatracker.model.PollStatus;
import com.dsatracker.repository.PollStatusRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Internal status endpoint (task 8.8, Requirement 9.2).
 *
 * <p><b>Endpoint:</b> {@code GET /api/status/poll} — returns the contents of the
 * {@code poll_status} table so polling breakage (e.g. a GFG scrape failure) is
 * visible without digging through logs. Read-only; delegates only to
 * {@link PollStatusRepository}.
 *
 * <p>Every {@link Platform} is always represented in the response, even when it
 * has no stored row yet: missing platforms are null-filled via
 * {@link PollStatusResponse#empty(Platform)}. This gives the frontend
 * "last synced X min ago" indicator (task 10.6) a stable, fixed-length shape to
 * render regardless of whether a platform has ever polled.
 *
 * <p><b>Security note:</b> this endpoint exposes internal polling health and is
 * currently unauthenticated (no auth layer exists in the project yet). That is
 * acceptable for a small side project but should be locked down before any
 * production exposure.
 *
 * <p>CORS is configured globally in task 12.4; no per-controller CORS is added here.
 */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final PollStatusRepository pollStatusRepository;

    public StatusController(PollStatusRepository pollStatusRepository) {
        this.pollStatusRepository = pollStatusRepository;
    }

    /**
     * Returns one status entry per platform, ordered by {@link Platform} declaration
     * order, merging stored rows with null-filled placeholders for platforms that
     * have never polled.
     *
     * @return the per-platform poll status list
     */
    @GetMapping("/poll")
    public List<PollStatusResponse> pollStatus() {
        Instant now = Instant.now();

        Map<Platform, PollStatus> stored = new EnumMap<>(Platform.class);
        for (PollStatus status : pollStatusRepository.findAll()) {
            stored.put(status.getPlatform(), status);
        }

        List<PollStatusResponse> result = new ArrayList<>(Platform.values().length);
        for (Platform platform : Platform.values()) {
            PollStatus status = stored.get(platform);
            result.add(status == null
                    ? PollStatusResponse.empty(platform)
                    : PollStatusResponse.from(status, now));
        }
        return result;
    }
}
