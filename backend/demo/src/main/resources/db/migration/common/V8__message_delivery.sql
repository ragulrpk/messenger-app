-- Newest message from others that has reached each member's device.
ALTER TABLE chat_members ADD COLUMN last_delivered_sequence BIGINT NOT NULL DEFAULT 0;

-- Existing history counts as delivered so old messages do not show as only sent.
UPDATE chat_members SET last_delivered_sequence = COALESCE(
    (SELECT MAX(m.sequence) FROM chat_messages m WHERE m.conversation_id = chat_members.conversation_id), 0);
