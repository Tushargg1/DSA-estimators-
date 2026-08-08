CREATE TABLE github_workflow_saves (
    user_id        BIGINT NOT NULL,
    request_token  CHAR(36) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    requested_at   DATETIME(6) NOT NULL,
    lease_until    DATETIME(6) NULL,
    last_saved_at  DATETIME(6) NULL,
    last_error     VARCHAR(500) NULL,
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_github_workflow_saves_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_github_workflow_saves_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    INDEX idx_github_workflow_saves_pending (status, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
