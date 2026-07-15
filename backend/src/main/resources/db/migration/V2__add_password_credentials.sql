-- Existing rows remain locked until an operator-assisted activation succeeds.
ALTER TABLE users
    ADD COLUMN password_hash VARCHAR(60) NULL AFTER email,
    ADD COLUMN credentials_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER password_hash;

-- The existing unique email index uses MySQL's case-insensitive utf8mb4 collation.
-- Normalize stored values once; all application writes also normalize with Locale.ROOT.
UPDATE users SET email = LOWER(TRIM(email));
