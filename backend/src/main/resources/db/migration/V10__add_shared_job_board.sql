CREATE TABLE job_listings (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    title       VARCHAR(200) NOT NULL,
    company     VARCHAR(200) NOT NULL,
    job_url     VARCHAR(2048) NOT NULL,
    posted_by   BIGINT NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_job_listings_poster FOREIGN KEY (posted_by) REFERENCES users (id),
    INDEX idx_job_listings_created (created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE job_applications (
    listing_id  BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    applied_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (listing_id, user_id),
    CONSTRAINT fk_job_applications_listing FOREIGN KEY (listing_id)
        REFERENCES job_listings (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_applications_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_job_applications_user (user_id, listing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
