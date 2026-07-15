-- Initial schema for a fresh MySQL 8 database. Flyway is the schema owner;
-- Hibernate runs with ddl-auto=validate. DATETIME(6) stores UTC instants with
-- microsecond precision, and JSON stores nullable submission tag arrays.

CREATE TABLE users (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    name                VARCHAR(100) NOT NULL,
    email               VARCHAR(150) NOT NULL,
    leetcode_username   VARCHAR(100),
    codeforces_username VARCHAR(100),
    gfg_username        VARCHAR(100),
    daily_target        INT NOT NULL DEFAULT 5,
    onboarding_complete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE submissions (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    user_id             BIGINT NOT NULL,
    platform            VARCHAR(20) NOT NULL,
    problem_id          VARCHAR(150) NOT NULL,
    problem_name        VARCHAR(255) NOT NULL,
    difficulty          VARCHAR(20),
    tags                JSON,
    solved_at_utc       DATETIME(6) NOT NULL,
    is_first_attempt    BOOLEAN NOT NULL,
    counted_for_target  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_submissions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_submissions_natural_key
        UNIQUE (user_id, platform, problem_id, solved_at_utc),
    INDEX idx_submissions_user_date (user_id, solved_at_utc),
    INDEX idx_submissions_first_attempt (user_id, platform, problem_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tracker_groups (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    invite_code VARCHAR(20) NOT NULL,
    created_by  BIGINT NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_tracker_groups_invite_code UNIQUE (invite_code),
    CONSTRAINT fk_tracker_groups_created_by FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE group_members (
    group_id  BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (group_id, user_id),
    CONSTRAINT fk_group_members_group
        FOREIGN KEY (group_id) REFERENCES tracker_groups (id),
    CONSTRAINT fk_group_members_user
        FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE daily_counts (
    user_id    BIGINT NOT NULL,
    date_ist   DATE NOT NULL,
    count      INT NOT NULL DEFAULT 0,
    target_hit BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, date_ist),
    CONSTRAINT fk_daily_counts_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE poll_status (
    platform            VARCHAR(20) NOT NULL,
    last_success_at     DATETIME(6),
    last_failure_at     DATETIME(6),
    last_failure_reason TEXT,
    PRIMARY KEY (platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;