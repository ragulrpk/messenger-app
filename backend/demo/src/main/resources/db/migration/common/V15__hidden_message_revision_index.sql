-- Supports bounded per-user removal pages without scanning every hidden message.
CREATE INDEX chat_message_hidden_revision_idx ON chat_message_hidden(user_id,revision);
