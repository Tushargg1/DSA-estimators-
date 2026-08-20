-- Add experience_required column to job_listings.
-- NULL means no explicit experience requirement (show to everyone).
-- A non-null integer means minimum years of non-internship experience required.
ALTER TABLE job_listings ADD COLUMN experience_required INT NULL AFTER source_id;
