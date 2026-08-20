-- Structured job metadata pulled from portal APIs (e.g. Accenture's findjobs endpoint).
-- These replace the guesswork of regex-scraping raw HTML with real portal fields.

-- external_id is the portal's stable per-job identifier (Accenture: requisitionId).
-- It is the dedup key: re-fetching the same job is a no-op instead of a duplicate row.
ALTER TABLE job_listings ADD COLUMN external_id VARCHAR(120) NULL AFTER source_id;
ALTER TABLE job_listings ADD COLUMN location VARCHAR(300) NULL AFTER description;
ALTER TABLE job_listings ADD COLUMN employment_type VARCHAR(100) NULL AFTER location;
ALTER TABLE job_listings ADD COLUMN career_level VARCHAR(100) NULL AFTER employment_type;
ALTER TABLE job_listings ADD COLUMN qualification VARCHAR(500) NULL AFTER career_level;
ALTER TABLE job_listings ADD COLUMN posted_text VARCHAR(120) NULL AFTER qualification;

-- Scoped unique per source so two portals reusing an id string don't collide.
ALTER TABLE job_listings
    ADD CONSTRAINT uq_job_listings_source_external UNIQUE (source_id, external_id);

-- Resumable chunked sync state. A sweep pages through the portal in fixed-size
-- chunks; the cursor is persisted after each chunk so a run killed by a free-tier
-- timeout resumes exactly where it stopped instead of restarting.
ALTER TABLE job_sources ADD COLUMN sync_cursor INT NOT NULL DEFAULT 0 AFTER last_error;
ALTER TABLE job_sources ADD COLUMN sweep_completed_at DATETIME(6) NULL AFTER sync_cursor;
ALTER TABLE job_sources ADD COLUMN adapter VARCHAR(40) NULL AFTER sweep_completed_at;
