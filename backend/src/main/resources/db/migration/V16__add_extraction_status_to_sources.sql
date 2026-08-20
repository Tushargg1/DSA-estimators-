-- Records whether jobs could actually be extracted from a source, so the UI can flag
-- sites that need a dedicated adapter instead of leaving them silently empty.
--   FULL    - a portal API adapter handled it
--   LIMITED - only generic HTML scraping worked, details may be missing
--   NONE    - nothing extractable (typically a JavaScript-rendered page)
--   ERROR   - the site could not be fetched
ALTER TABLE job_sources ADD COLUMN extraction_status VARCHAR(20) NULL AFTER adapter;
