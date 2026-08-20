-- Add description column to job_listings to store surrounding page text captured
-- during scraping. Used for keyword matching against profile skills beyond just
-- the job title (e.g. "java" mentioned in the description but not the title).
ALTER TABLE job_listings ADD COLUMN description TEXT NULL AFTER job_url;
