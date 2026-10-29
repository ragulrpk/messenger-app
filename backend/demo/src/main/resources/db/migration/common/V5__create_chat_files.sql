-- File metadata; the bytes live on the shared file volume under storage_key.
CREATE TABLE chat_files (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES chat_conversations(id),
    uploader_id UUID NOT NULL REFERENCES app_users(id),
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT file_size_positive CHECK (size_bytes > 0)
);
CREATE INDEX chat_files_expiry_idx ON chat_files(expires_at);

-- A message carries text, one file, or both.
ALTER TABLE chat_messages ADD COLUMN file_id UUID;
ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_file_fk FOREIGN KEY (file_id) REFERENCES chat_files(id);
ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_file_unique UNIQUE (file_id);
ALTER TABLE chat_messages DROP CONSTRAINT message_not_blank;
ALTER TABLE chat_messages ADD CONSTRAINT message_has_content CHECK (length(trim(body)) > 0 OR file_id IS NOT NULL);
