-- Job profiles: each user can create multiple role-based profiles with keywords for matching.
CREATE TABLE job_profiles (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    user_id     BIGINT NOT NULL,
    role_title  VARCHAR(200) NOT NULL,
    keywords    TEXT NOT NULL COMMENT 'Comma-separated keywords extracted from resume / entered manually',
    resume_text MEDIUMTEXT NULL COMMENT 'Optional pasted resume text for keyword extraction',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_job_profiles_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_job_profiles_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Job sources: career page URLs that are periodically scraped for new listings.
CREATE TABLE job_sources (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    added_by    BIGINT NOT NULL,
    url         VARCHAR(2048) NOT NULL,
    label       VARCHAR(200) NULL COMMENT 'Optional user-friendly label',
    last_scraped_at DATETIME(6) NULL,
    last_error  VARCHAR(500) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_job_sources_user FOREIGN KEY (added_by) REFERENCES users (id),
    INDEX idx_job_sources_added (added_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Track which listings were auto-scraped from a source.
ALTER TABLE job_listings ADD COLUMN source_id BIGINT NULL AFTER posted_by;
ALTER TABLE job_listings ADD INDEX idx_job_listings_source (source_id);
ALTER TABLE job_listings ADD CONSTRAINT fk_job_listings_source
    FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE SET NULL;
