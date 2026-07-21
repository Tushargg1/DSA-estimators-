CREATE TABLE github_connections (
    user_id                  BIGINT NOT NULL,
    installation_id          BIGINT NULL,
    account_id               BIGINT NULL,
    account_login            VARCHAR(255) NULL,
    repository_id            BIGINT NULL,
    repository_full_name     VARCHAR(255) NULL,
    default_branch           VARCHAR(255) NULL,
    extension_token_hash     VARCHAR(64) NULL,
    connect_state_hash       VARCHAR(64) NULL,
    connect_state_expires_at DATETIME(6) NULL,
    created_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_github_connections_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_github_connections_installation UNIQUE (installation_id),
    CONSTRAINT uq_github_connections_extension_token UNIQUE (extension_token_hash),
    INDEX idx_github_connections_state (connect_state_hash, connect_state_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE solution_captures (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    user_id      BIGINT NOT NULL,
    platform     VARCHAR(20) NOT NULL,
    problem_id   VARCHAR(150) NOT NULL,
    problem_name VARCHAR(255) NOT NULL,
    problem_url  VARCHAR(2048) NOT NULL,
    language     VARCHAR(100) NOT NULL,
    difficulty   VARCHAR(30) NULL,
    tags         JSON NULL,
    pattern_slug VARCHAR(100) NOT NULL,
    source_code  MEDIUMTEXT NULL,
    solved_at_utc DATETIME(6) NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_solution_captures_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_solution_captures_problem UNIQUE (user_id, platform, problem_id),
    INDEX idx_solution_captures_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE github_export_jobs (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    capture_id      BIGINT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_until     DATETIME(6) NULL,
    claim_token     VARCHAR(36) NULL,
    last_error      VARCHAR(500) NULL,
    exported_at     DATETIME(6) NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_github_export_jobs_capture
        FOREIGN KEY (capture_id) REFERENCES solution_captures (id),
    CONSTRAINT uq_github_export_jobs_capture UNIQUE (capture_id),
    CONSTRAINT chk_github_export_jobs_attempts CHECK (attempts >= 0 AND attempts <= 8),
    INDEX idx_github_export_jobs_due (status, next_attempt_at),
    INDEX idx_github_export_jobs_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
