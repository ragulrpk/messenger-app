-- An announcement group lets only its admins post; other members read and react.
ALTER TABLE chat_conversations ADD COLUMN admins_only BOOLEAN NOT NULL DEFAULT FALSE;
