package com.dsatracker.adapter;

import java.time.Instant;
import java.util.List;

/**
 * A single submission fetched from an external platform, before it becomes a
 * persisted {@link com.dsatracker.model.Submission} entity.
 *
 * <p>This DTO is intentionally decoupled from the JPA layer: adapters produce
 * {@code RawSubmission} instances and the polling/backfill logic is responsible
 * for translating them into {@code Submission} rows (assigning user, computing
 * {@code is_first_attempt}, {@code counted_for_target}, etc.).
 *
 * <p>{@code problemId}, {@code problemName} and {@code solvedAt} are always
 * populated. {@code difficulty} and {@code tags} are best-effort metadata:
 * LeetCode and Codeforces can supply them, GFG typically cannot and will leave
 * them {@code null} (rendered as "Not available" downstream, per Requirement 8.2).
 *
 * @param problemId    platform-specific unique problem key (slug or id)
 * @param problemName  human-readable problem title
 * @param solvedAt     when the problem was solved, in UTC
 * @param difficulty   problem difficulty, or {@code null} if unavailable
 * @param tags         problem tags, or {@code null} if unavailable
 */
public record RawSubmission(
        String problemId,
        String problemName,
        Instant solvedAt,
        String difficulty,
        List<String> tags
) {
    /**
     * Convenience factory for adapters that only have the required fields
     * (e.g. the GFG scraper) and no difficulty/tag metadata.
     */
    public static RawSubmission of(String problemId, String problemName, Instant solvedAt) {
        return new RawSubmission(problemId, problemName, solvedAt, null, null);
    }
}
