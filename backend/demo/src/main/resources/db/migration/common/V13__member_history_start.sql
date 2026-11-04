-- Members see only messages after this sequence: people added to (or re-added to) a group do not
-- see what was said before they joined. Existing members keep the history they already had.
ALTER TABLE chat_members ADD COLUMN history_from_sequence BIGINT NOT NULL DEFAULT 0;
