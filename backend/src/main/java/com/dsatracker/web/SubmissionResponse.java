package com.dsatracker.web;

import com.dsatracker.model.Submission;

import java.time.Instant;
import java.util.List;

/**
 * Response representation of a single {@link Submission} for the paginated
 * submissions API ({@code GET /api/users/{id}/submissions}, task 8.7).
 *
 * <p>Exposes exactly the fields the problem-detail view needs (Requirement 8.1):
 * problem name, platform, difficulty, tags, and the solve timestamp — plus the
 * two counting flags ({@code isFirstAttempt}, {@code countedForTarget}) so the
 * client can distinguish genuinely-new solves from re-solves and backfilled rows.
 *
 * <p>{@code difficulty} and {@code tags} are intentionally left {@code null} when
 * the platform did not provide them (e.g. a GFG scrape gap). Per Requirement 8.2
 * the frontend renders these as "Not available" rather than a blank or error;
 * this DTO passes the {@code null} through unchanged rather than substituting a
 * placeholder, keeping the "missing vs empty" distinction on the client side.
 *
 * @param problemId        platform-specific unique problem key (slug or id)
 * @param problemName      human-readable problem title
 * @param platform         source platform name ({@code LEETCODE|CODEFORCES|GFG})
 * @param difficulty       problem difficulty, or {@code null} if unavailable
 * @param tags             problem tags, or {@code null} if unavailable
 * @param solvedAtUtc      when the problem was solved, in UTC
 * @param isFirstAttempt   whether this was the user's first-ever solve of this problem
 * @param countedForTarget whether this submission incremented the user's daily count
 */
public record SubmissionResponse(
        String problemId,
        String problemName,
        String platform,
        String difficulty,
        List<String> tags,
        Instant solvedAtUtc,
        boolean isFirstAttempt,
        boolean countedForTarget
) {
    /** Maps a persisted {@link Submission} entity to its API representation. */
    public static SubmissionResponse from(Submission submission) {
        return new SubmissionResponse(
                submission.getProblemId(),
                submission.getProblemName(),
                submission.getPlatform() == null ? null : submission.getPlatform().name(),
                submission.getDifficulty(),
                submission.getTags(),
                submission.getSolvedAtUtc(),
                submission.isFirstAttempt(),
                submission.isCountedForTarget());
    }
}
