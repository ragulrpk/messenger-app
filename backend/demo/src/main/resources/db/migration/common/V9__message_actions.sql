-- Each conversation counts changes to existing messages (edit, delete, reaction) so clients
-- can fetch only what changed since the revision they last saw.
ALTER TABLE chat_conversations ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chat_messages ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
CREATE INDEX chat_messages_revision_idx ON chat_messages(conversation_id, revision);

ALTER TABLE chat_messages ADD COLUMN edited_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE chat_messages ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE chat_messages ADD COLUMN forwarded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_messages ADD COLUMN reply_to_id UUID;
ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_reply_fk
    FOREIGN KEY (reply_to_id) REFERENCES chat_messages(id) ON DELETE SET NULL;

-- A message deleted for everyone keeps its row, with its text wiped.
ALTER TABLE chat_messages DROP CONSTRAINT message_has_content;
ALTER TABLE chat_messages ADD CONSTRAINT message_has_content
    CHECK (length(trim(body)) > 0 OR file_id IS NOT NULL OR deleted_at IS NOT NULL);

-- One reaction per person per message.
CREATE TABLE chat_reactions (
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id),
    emoji VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (message_id, user_id)
);

-- Messages a member deleted for themselves only; revision lets their other devices catch up.
CREATE TABLE chat_message_hidden (
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id),
    revision BIGINT NOT NULL,
    PRIMARY KEY (message_id, user_id)
);
