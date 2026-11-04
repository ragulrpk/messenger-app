-- Notification positions are now sending-transaction IDs instead of chat_notification_clock values, so sends
-- no longer queue behind one counter row. Old positions are not comparable with transaction IDs: drop the
-- short-lived notification rows and restart push cursors before the new numbering begins.
DELETE FROM chat_notifications;
UPDATE chat_push_subscriptions SET cursor_position=0;
