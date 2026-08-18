package com.dsatracker.jobs;

import java.time.Instant;

public final class JobDtos {
    private JobDtos() { }

    public record CreateRequest(String title, String company, String jobUrl) { }

    public record JobResponse(
            Long id,
            String title,
            String company,
            String jobUrl,
            String postedByName,
            Instant createdAt,
            boolean applied,
            Instant appliedAt
    ) { }
}
