-- An expired file whose bytes could not be removed waits until this time, so it cannot hold back the others.
ALTER TABLE chat_files ADD COLUMN purge_retry_at TIMESTAMP WITH TIME ZONE;
-- The daily upload quota sums one sender's recent uploads.
CREATE INDEX chat_files_uploader_idx ON chat_files(uploader_id, created_at);
