-- Conversations are either a direct pair (unique direct_key) or a named group.
ALTER TABLE chat_conversations ADD COLUMN type VARCHAR(10) NOT NULL DEFAULT 'DIRECT';
ALTER TABLE chat_conversations ADD COLUMN name VARCHAR(100);
ALTER TABLE chat_conversations ADD COLUMN created_by UUID REFERENCES app_users(id);
ALTER TABLE chat_conversations ALTER COLUMN direct_key DROP NOT NULL;

-- Group members are admins or members. Someone who leaves or is removed keeps their
-- row (their messages still reference it) with left_at set, and loses all access.
ALTER TABLE chat_members ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'member';
ALTER TABLE chat_members ADD COLUMN joined_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE chat_members ADD COLUMN left_at TIMESTAMP WITH TIME ZONE;
