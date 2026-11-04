-- One-time recovery for the original local V7 presence schema.
-- Run with psql -v ON_ERROR_STOP=1 -f scripts/repair-local-v7.sql.
-- Keep applied migrations immutable; put future schema changes in a new version.
\set ON_ERROR_STOP on
BEGIN;
LOCK TABLE flyway_schema_history IN EXCLUSIVE MODE;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history
                   WHERE version = '7' AND checksum = -1725651662 AND success) THEN
        RAISE EXCEPTION 'Expected original V7 checksum -1725651662; no changes made';
    END IF;
    IF (SELECT count(*) FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'chat_members'
          AND column_name IN ('pinned','muted','hidden','marked_unread','last_read_sequence')) <> 5 THEN
        RAISE EXCEPTION 'Unexpected member schema; no changes made';
    END IF;
END $$;

CREATE TABLE user_presence (
    user_id UUID PRIMARY KEY REFERENCES app_users(id),
    status VARCHAR(16) NOT NULL,
    note VARCHAR(160) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Preserve the original presence values and leave their old columns intact.
INSERT INTO user_presence(user_id, status, note, updated_at)
SELECT id, presence_status, status_note, CURRENT_TIMESTAMP FROM app_users;

UPDATE flyway_schema_history SET checksum = -567482017
WHERE version = '7' AND checksum = -1725651662 AND success;
COMMIT;
