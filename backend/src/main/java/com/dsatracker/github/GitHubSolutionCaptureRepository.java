package com.dsatracker.github;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface GitHubSolutionCaptureRepository
        extends JpaRepository<GitHubSolutionCapture, Long> {
    Optional<GitHubSolutionCapture> findByUserIdAndPlatformAndProblemId(
            Long userId, GitHubPlatform platform, String problemId);

    List<GitHubSolutionCapture> findByUserIdOrderBySolvedAtUtcAsc(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO solution_captures
              (user_id, platform, problem_id, problem_name, problem_url, language,
               difficulty, tags, pattern_slug, source_code, solved_at_utc,
               source_updated_at_utc, created_at)
            VALUES (:userId, :platform, :problemId, :problemName, :problemUrl, :language,
                    :difficulty, CAST(:tags AS JSON), :patternSlug, :sourceCode,
                    :solvedAt, :solvedAt, UTC_TIMESTAMP(6))
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("platform") String platform,
                       @Param("problemId") String problemId,
                       @Param("problemName") String problemName,
                       @Param("problemUrl") String problemUrl,
                       @Param("language") String language,
                       @Param("difficulty") String difficulty,
                       @Param("tags") String tags,
                       @Param("patternSlug") String patternSlug,
                       @Param("sourceCode") String sourceCode,
                       @Param("solvedAt") Instant solvedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE solution_captures
            SET problem_name = IF(:solvedAt >= source_updated_at_utc, :problemName, problem_name),
                problem_url = IF(:solvedAt >= source_updated_at_utc, :problemUrl, problem_url),
                language = IF(:solvedAt >= source_updated_at_utc, :language, language),
                difficulty = IF(:solvedAt >= source_updated_at_utc, :difficulty, difficulty),
                tags = IF(:solvedAt >= source_updated_at_utc, CAST(:tags AS JSON), tags),
                pattern_slug = IF(:solvedAt >= source_updated_at_utc, :patternSlug, pattern_slug),
                source_code = IF(:solvedAt >= source_updated_at_utc, :sourceCode, source_code),
                solved_at_utc = LEAST(solved_at_utc, :solvedAt),
                source_updated_at_utc = GREATEST(source_updated_at_utc, :solvedAt)
            WHERE user_id = :userId AND platform = :platform AND problem_id = :problemId
              AND (solved_at_utc > :solvedAt OR
                   (:solvedAt >= source_updated_at_utc AND NOT (
                     problem_name <=> :problemName AND problem_url <=> :problemUrl AND
                     language <=> :language AND difficulty <=> :difficulty AND
                     tags <=> CAST(:tags AS JSON) AND pattern_slug <=> :patternSlug AND
                     source_code <=> :sourceCode)))
            """, nativeQuery = true)
    int updateIfChanged(@Param("userId") Long userId,
                        @Param("platform") String platform,
                        @Param("problemId") String problemId,
                        @Param("problemName") String problemName,
                        @Param("problemUrl") String problemUrl,
                        @Param("language") String language,
                        @Param("difficulty") String difficulty,
                        @Param("tags") String tags,
                        @Param("patternSlug") String patternSlug,
                        @Param("sourceCode") String sourceCode,
                        @Param("solvedAt") Instant solvedAt);
}
