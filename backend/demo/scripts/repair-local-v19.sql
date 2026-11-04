-- One-time recovery for the original local V19 employee-login schema.
-- Run from backend/demo with psql -v ON_ERROR_STOP=1 -f scripts/repair-local-v19.sql.
-- Keep applied migrations immutable; put future schema changes in a new version.
\set ON_ERROR_STOP on
BEGIN;
LOCK TABLE flyway_schema_history IN EXCLUSIVE MODE;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history
                   WHERE version = '19' AND checksum = 1090783504 AND success) THEN
        RAISE EXCEPTION 'Expected original V19 checksum 1090783504; no changes made';
    END IF;
    IF (SELECT count(*) FROM pg_constraint
        WHERE (conrelid, conname) IN (
            ('one_time_codes'::regclass, 'one_time_codes_user_id_fkey'),
            ('sign_in_events'::regclass, 'sign_in_events_user_id_fkey'))
          AND contype = 'f' AND confrelid = 'app_users'::regclass
          AND confdeltype = 'a'
          AND pg_get_constraintdef(oid) = 'FOREIGN KEY (user_id) REFERENCES app_users(id)') <> 2 THEN
        RAISE EXCEPTION 'Unexpected V19 account foreign keys; no changes made';
    END IF;
END $$;

ALTER TABLE one_time_codes DROP CONSTRAINT one_time_codes_user_id_fkey;
ALTER TABLE one_time_codes ADD CONSTRAINT one_time_codes_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE;
ALTER TABLE sign_in_events DROP CONSTRAINT sign_in_events_user_id_fkey;
ALTER TABLE sign_in_events ADD CONSTRAINT sign_in_events_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE;

UPDATE flyway_schema_history SET checksum = 482232796
WHERE version = '19' AND checksum = 1090783504 AND success;
COMMIT;
