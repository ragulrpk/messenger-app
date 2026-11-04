-- A message can carry several files sent together. chat_messages.file_id stays the first file, so previews,
-- quotes and notifications keep working; every file of a message points back to it, in the order it was sent.
ALTER TABLE chat_files ADD COLUMN message_id UUID;
ALTER TABLE chat_files ADD COLUMN file_order INTEGER NOT NULL DEFAULT 0;
UPDATE chat_files SET message_id=(SELECT m.id FROM chat_messages m WHERE m.file_id=chat_files.id);
ALTER TABLE chat_files ADD CONSTRAINT chat_files_message_fk FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE SET NULL;
CREATE INDEX chat_files_message_idx ON chat_files(message_id, file_order);
