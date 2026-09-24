DROP INDEX IF EXISTS chat_messages_history_idx;

CREATE INDEX chat_messages_history_idx
    ON chat_messages (conversation_id, sequence DESC)
    INCLUDE (id, sender_id, client_id, body, sent_at);
