package com.dsatracker.github;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "solution_captures")
public class GitHubSolutionCapture {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    private GitHubPlatform platform;
    @Column(name = "problem_id", nullable = false, length = 150)
    private String problemId;
    @Column(name = "problem_name", nullable = false, length = 255)
    private String problemName;
    @Column(name = "problem_url", nullable = false, length = 2048)
    private String problemUrl;
    @Column(name = "language", nullable = false, length = 100)
    private String language;
    @Column(name = "difficulty", length = 30)
    private String difficulty;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "json")
    private List<String> tags;
    @Column(name = "pattern_slug", nullable = false, length = 100)
    private String patternSlug;
    @Column(name = "source_code", columnDefinition = "MEDIUMTEXT")
    private String sourceCode;
    @Column(name = "solved_at_utc", nullable = false)
    private Instant solvedAtUtc;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GitHubSolutionCapture() { }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public GitHubPlatform getPlatform() { return platform; }
    public String getProblemId() { return problemId; }
    public String getProblemName() { return problemName; }
    public String getProblemUrl() { return problemUrl; }
    public String getLanguage() { return language; }
    public String getDifficulty() { return difficulty; }
    public String getSourceCode() { return sourceCode; }
    public List<String> getTags() { return tags; }
    public String getPatternSlug() { return patternSlug; }
    public Instant getSolvedAtUtc() { return solvedAtUtc; }
    public void clearSourceCode() { sourceCode = null; }
}
