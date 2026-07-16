ALTER TABLE tracker_groups
    ADD COLUMN daily_target INT NOT NULL DEFAULT 3,
    ADD COLUMN target_mode VARCHAR(10) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN target_calculated_for_date DATE NULL,
    ADD COLUMN poll_version INT NOT NULL DEFAULT 0,
    ADD COLUMN poll_active BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN poll_started_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_tracker_groups_daily_target CHECK (daily_target >= 3);

CREATE TABLE group_target_votes (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    group_id        BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    poll_version    INT NOT NULL,
    proposed_target INT NOT NULL,
    voted_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_group_target_vote UNIQUE (group_id, user_id, poll_version),
    CONSTRAINT fk_group_target_votes_group FOREIGN KEY (group_id) REFERENCES tracker_groups (id),
    CONSTRAINT fk_group_target_votes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_group_vote_target CHECK (proposed_target >= 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;