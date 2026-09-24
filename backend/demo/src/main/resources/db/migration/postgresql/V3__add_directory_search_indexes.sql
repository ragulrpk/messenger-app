CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX app_users_display_name_trgm_idx
    ON app_users USING gin (lower(display_name) gin_trgm_ops)
    WHERE enabled = true;

CREATE INDEX app_users_username_trgm_idx
    ON app_users USING gin (username gin_trgm_ops)
    WHERE enabled = true;

CREATE INDEX chat_conversations_recency_idx
    ON chat_conversations (updated_at DESC, id DESC);
