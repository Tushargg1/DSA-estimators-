package com.dsatracker.github;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface GitHubSolutionCaptureRepository
        extends JpaRepository<GitHubSolutionCapture, Long> {
    Optional<GitHubSolutionCapture> findByUserIdAndPlatformAndProblemId(
            Long userId, GitHubPlatform platform, String problemId);

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO solution_captures
              (user_id, platform, problem_id, problem_name, problem_url, language,
               difficulty, tags, pattern_slug, source_code, solved_at_utc, created_at)
            VALUES (:userId, :platform, :problemId, :problemName, :problemUrl, :language,
                    :difficulty, CAST(:tags AS JSON), :patternSlug, :sourceCode,
                    :solvedAt, UTC_TIMESTAMP(6))
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
}
