-- Google OpenID Connect subject is the stable account identifier; email can change.
ALTER TABLE users
    ADD COLUMN google_subject VARCHAR(255) NULL AFTER email,
    ADD CONSTRAINT uq_users_google_subject UNIQUE (google_subject);
