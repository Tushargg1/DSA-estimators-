CREATE TABLE github_progress_push_schedules (
    user_id      BIGINT NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    first_time   TIME NOT NULL DEFAULT '09:00:00',
    second_time  TIME NOT NULL DEFAULT '21:00:00',
    timezone     VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
    next_run_at  DATETIME(6) NULL,
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_github_progress_push_schedules_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_github_progress_push_schedule_times
        CHECK (first_time < second_time
            AND SECOND(first_time) = 0 AND SECOND(second_time) = 0),
    CONSTRAINT chk_github_progress_push_schedule_timezone
        CHECK (timezone = 'Asia/Kolkata'),
    INDEX idx_github_progress_push_schedules_due (enabled, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE github_progress_push_history (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    user_id        BIGINT NOT NULL,
    request_token  VARCHAR(36) NOT NULL,
    trigger_type   VARCHAR(20) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    requested_at   DATETIME(6) NOT NULL,
    started_at     DATETIME(6) NULL,
    completed_at   DATETIME(6) NULL,
    commit_sha     VARCHAR(64) NULL,
    commit_url     VARCHAR(2048) NULL,
    changed_files  INT NULL,
    last_error     VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_github_progress_push_history_token UNIQUE (request_token),
    CONSTRAINT fk_github_progress_push_history_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_github_progress_push_history_trigger
        CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT chk_github_progress_push_history_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_github_progress_push_history_changed_files
        CHECK (changed_files IS NULL OR changed_files >= 0),
    CONSTRAINT chk_github_progress_push_history_timestamps
        CHECK ((started_at IS NULL OR started_at >= requested_at)
            AND (completed_at IS NULL OR completed_at >= requested_at)),
    INDEX idx_github_progress_push_history_user_requested
        (user_id, requested_at, id),
    INDEX idx_github_progress_push_history_status_requested
        (status, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Existing users receive the enabled twice-daily IST defaults. The arithmetic
-- uses IST's fixed +05:30 offset while DATETIME values remain UTC.
INSERT INTO github_progress_push_schedules
    (user_id, enabled, first_time, second_time, timezone, next_run_at, updated_at)
SELECT id, TRUE, '09:00:00', '21:00:00', 'Asia/Kolkata',
       CASE
           WHEN TIME(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 330 MINUTE)) < '09:00:00'
               THEN DATE_SUB(TIMESTAMP(
                       DATE(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 330 MINUTE)),
                       '09:00:00'), INTERVAL 330 MINUTE)
           WHEN TIME(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 330 MINUTE)) < '21:00:00'
               THEN DATE_SUB(TIMESTAMP(
                       DATE(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 330 MINUTE)),
                       '21:00:00'), INTERVAL 330 MINUTE)
           ELSE DATE_SUB(TIMESTAMP(
                    DATE_ADD(DATE(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 330 MINUTE)),
                             INTERVAL 1 DAY),
                    '09:00:00'), INTERVAL 330 MINUTE)
       END,
       UTC_TIMESTAMP(6)
FROM users;

-- Preserve the currently visible workflow-save lifecycle as the first history
-- entry when upgrading an installation that already has pending/completed work.
INSERT INTO github_progress_push_history
    (user_id, request_token, trigger_type, status, requested_at,
     started_at, completed_at, last_error)
SELECT user_id, request_token, 'MANUAL', status, requested_at,
       CASE WHEN status IN ('RUNNING', 'SUCCEEDED', 'FAILED')
            THEN updated_at ELSE NULL END,
       CASE
           WHEN status = 'SUCCEEDED' THEN COALESCE(last_saved_at, updated_at)
           WHEN status = 'FAILED' THEN updated_at
           ELSE NULL
       END,
       last_error
FROM github_workflow_saves;