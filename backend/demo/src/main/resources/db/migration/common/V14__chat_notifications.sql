-- A shared counter is locked until the sending transaction commits, so reconnect cursors cannot skip late commits.
CREATE TABLE chat_notification_clock (id INTEGER PRIMARY KEY, position BIGINT NOT NULL);
INSERT INTO chat_notification_clock(id,position) VALUES (1,0);
CREATE TABLE chat_notifications (
    position BIGINT NOT NULL,
    user_id UUID NOT NULL REFERENCES app_users(id),
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    mentioned BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, position)
);
CREATE INDEX chat_notifications_message_idx ON chat_notifications(message_id);
CREATE TABLE chat_push_subscriptions (
    id VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    session_id VARCHAR(128) NOT NULL,
    credential_stamp VARCHAR(128) NOT NULL,
    endpoint VARCHAR(2048) NOT NULL,
    public_key VARCHAR(128) NOT NULL,
    auth_secret VARCHAR(64) NOT NULL,
    cursor_position BIGINT NOT NULL,
    failures INTEGER NOT NULL DEFAULT 0,
    retry_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX chat_push_due_idx ON chat_push_subscriptions(retry_at);
