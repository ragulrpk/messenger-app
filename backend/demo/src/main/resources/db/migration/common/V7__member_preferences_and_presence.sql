-- Per-member conversation preferences and read position.
ALTER TABLE chat_members ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_members ADD COLUMN muted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_members ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_members ADD COLUMN marked_unread BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_members ADD COLUMN last_read_sequence BIGINT NOT NULL DEFAULT 0;

-- Existing history counts as read so the upgrade does not flood every badge.
UPDATE chat_members SET last_read_sequence = COALESCE(
    (SELECT MAX(m.sequence) FROM chat_messages m WHERE m.conversation_id = chat_members.conversation_id), 0);

-- Self-chosen status and note, shown to the people a user chats with.
-- A user without a row is shown as online with no note. The API accepts only
-- online, offline, meeting and busy (H2 test databases reject an IN check here).
CREATE TABLE user_presence (
    user_id UUID PRIMARY KEY REFERENCES app_users(id),
    status VARCHAR(16) NOT NULL,
    note VARCHAR(160) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
