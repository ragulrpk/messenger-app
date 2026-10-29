-- History reads now include the attachment reference.
DROP INDEX IF EXISTS chat_messages_history_idx;

CREATE INDEX chat_messages_history_idx
    ON chat_messages (conversation_id, sequence DESC)
    INCLUDE (id, sender_id, client_id, body, sent_at, file_id);

-- The retention job only scans files that still have bytes on disk.
DROP INDEX IF EXISTS chat_files_expiry_idx;

CREATE INDEX chat_files_expiry_idx
    ON chat_files (expires_at)
    WHERE deleted_at IS NULL;
