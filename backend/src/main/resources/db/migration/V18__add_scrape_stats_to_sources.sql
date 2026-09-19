ALTER TABLE job_sources ADD COLUMN last_scrape_total_jobs INT DEFAULT 0;
ALTER TABLE job_sources ADD COLUMN last_scrape_matched_jobs INT DEFAULT 0;
