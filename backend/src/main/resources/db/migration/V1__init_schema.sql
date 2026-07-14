-- V1__init_schema.sql
-- Initial schema for the DSA Progress Tracker.
--
-- This migration is the single source of truth for the database schema. Spring
-- is configured with `spring.jpa.hibernate.ddl-auto=validate`, so the tables,
-- columns and types below MUST match the JPA entities under
-- com.dsatracker.model exactly (design.md "Data Models > Database Schema").
--
-- Conventions:
--   * BIGSERIAL primary keys back @GeneratedValue(strategy = IDENTITY) (Long id).
--   * TIMESTAMP (without time zone) backs java.time.Instant columns; the app
--     stores/reads everything in UTC (hibernate.jdbc.time_zone=UTC).
--   * Platform enums (Submission.platform, PollStatus.platform) are persisted as
--     their name() via @Enumerated(STRING) into VARCHAR(20).

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    email               VARCHAR(150) NOT NULL UNIQUE,
    leetcode_username   VARCHAR(100),
    codeforces_username VARCHAR(100),
    gfg_username        VARCHAR(100),
    daily_target        INTEGER NOT NULL DEFAULT 5,
    onboarding_complete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- submissions
-- ---------------------------------------------------------------------------
CREATE TABLE submissions (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    platform            VARCHAR(20) NOT NULL,   -- 'LEETCODE' | 'CODEFORCES' | 'GFG'
    problem_id          VARCHAR(150) NOT NULL,  -- slug or platform-specific id
    problem_name        VARCHAR(255) NOT NULL,
    difficulty          VARCHAR(20),            -- nullable; GFG may not have it
    tags                TEXT[],                 -- nullable
    solved_at_utc       TIMESTAMP NOT NULL,
    is_first_attempt    BOOLEAN NOT NULL,
    counted_for_target  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_submissions_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_submissions_natural_key
        UNIQUE (user_id, platform, problem_id, solved_at_utc)
);

CREATE INDEX idx_submissions_user_date
    ON submissions (user_id, solved_at_utc);
CREATE INDEX idx_submissions_first_attempt
    ON submissions (user_id, platform, problem_id);

-- ---------------------------------------------------------------------------
-- groups  ("group" is a reserved keyword, table is explicitly "groups")
-- ---------------------------------------------------------------------------
CREATE TABLE groups (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    invite_code VARCHAR(20) NOT NULL UNIQUE,
    created_by  BIGINT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_groups_created_by
        FOREIGN KEY (created_by) REFERENCES users (id)
);

-- ---------------------------------------------------------------------------
-- group_members  (join table, composite PK)
-- ---------------------------------------------------------------------------
CREATE TABLE group_members (
    group_id  BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id),
    CONSTRAINT fk_group_members_group
        FOREIGN KEY (group_id) REFERENCES groups (id),
    CONSTRAINT fk_group_members_user
        FOREIGN KEY (user_id) REFERENCES users (id)
);

-- ---------------------------------------------------------------------------
-- daily_counts  (denormalized per-user/per-day rollup, composite PK)
-- ---------------------------------------------------------------------------
CREATE TABLE daily_counts (
    user_id    BIGINT NOT NULL,
    date_ist   DATE NOT NULL,
    count      INTEGER NOT NULL DEFAULT 0,
    target_hit BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, date_ist),
    CONSTRAINT fk_daily_counts_user
        FOREIGN KEY (user_id) REFERENCES users (id)
);

-- ---------------------------------------------------------------------------
-- poll_status  (one row per platform; platform name is the PK)
-- ---------------------------------------------------------------------------
CREATE TABLE poll_status (
    platform            VARCHAR(20) PRIMARY KEY,
    last_success_at     TIMESTAMP,
    last_failure_at     TIMESTAMP,
    last_failure_reason TEXT
);
