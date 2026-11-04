-- The member count is stored on the conversation and updated when membership changes,
-- instead of being counted on every conversation-list poll.
ALTER TABLE chat_conversations ADD COLUMN member_count INTEGER NOT NULL DEFAULT 0;
UPDATE chat_conversations SET member_count = (
    SELECT count(*) FROM chat_members m WHERE m.conversation_id = chat_conversations.id AND m.left_at IS NULL);

-- Group receipts read the lowest read and delivered position among a group's members.
CREATE INDEX chat_members_read_idx ON chat_members(conversation_id, last_read_sequence);
CREATE INDEX chat_members_delivered_idx ON chat_members(conversation_id, last_delivered_sequence);
