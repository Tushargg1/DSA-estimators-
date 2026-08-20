-- Add resume_file_name column to job_profiles for tracking uploaded PDF file names.
ALTER TABLE job_profiles ADD COLUMN resume_file_name VARCHAR(500) NULL AFTER resume_text;
