ALTER TABLE solution_captures
    ADD COLUMN source_updated_at_utc DATETIME(6) NULL AFTER solved_at_utc;

UPDATE solution_captures
SET source_updated_at_utc = solved_at_utc
WHERE source_updated_at_utc IS NULL;

ALTER TABLE solution_captures
    MODIFY source_updated_at_utc DATETIME(6) NOT NULL;
