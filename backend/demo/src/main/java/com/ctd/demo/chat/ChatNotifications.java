package com.ctd.demo.chat;

import com.ctd.demo.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Durable, ordered notifications. Content and access are rechecked when a notification is delivered.
 *  Readers keep a cursor ("seen up to position N"), so no notification at or below a position a reader was
 *  handed may commit later. On PostgreSQL a notification's position is its sending transaction's ID, and
 *  readers stop just below the oldest transaction still running; sends never wait for each other. H2 has no
 *  transaction IDs, so there each send row-locks one shared counter until it commits. */
@Service
public class ChatNotifications {
    private final JdbcTemplate db;
    private final UserRepository users;
    private final boolean postgres;
    public ChatNotifications(JdbcTemplate db, UserRepository users) {
        this.db = db;
        this.users = users;
        this.postgres = Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection ->
                "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }
    public record Item(long position, UUID userId, UUID conversationId, UUID messageId, long messageSequence,
                       String title, String body, boolean mentioned) { }
    /** pending: newer notifications are committed but wait for an older transaction to finish; ask again shortly. */
    public record Page(List<Item> notifications, long cursor, boolean hasMore, boolean pending) { }

    /** The highest position that is safe to read up to: nothing at or below it can still be committed. */
    public long position() {
        // Every transaction that can still commit has an ID at or above the snapshot's xmin.
        if (postgres) return db.queryForObject("SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint - 1", Long.class);
        return db.queryForObject("SELECT position FROM chat_notification_clock WHERE id=1", Long.class);
    }

    /** Called in the same transaction as a new message; retries never create a second notification.
     *  At most one call per transaction: on PostgreSQL the transaction ID is the position. */
    public void record(ChatService.Message message, Set<String> mentions) {
        var recipients = db.query("SELECT cm.user_id,u.username FROM chat_members cm JOIN app_users u ON u.id=cm.user_id "
                + "WHERE cm.conversation_id=? AND cm.left_at IS NULL AND cm.user_id<>? AND u.enabled=true",
                (rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)), messageConversation(message.id()), message.senderId());
        if (recipients.isEmpty()) return;
        long position;
        if (postgres) position = db.queryForObject("SELECT pg_current_xact_id()::text::bigint", Long.class);
        else {
            db.update("UPDATE chat_notification_clock SET position=position+1 WHERE id=1");
            position = position();
        }
        for (var recipient : recipients) db.update("INSERT INTO chat_notifications(position,user_id,message_id,mentioned,created_at) VALUES (?,?,?,?,?)",
                position, recipient.getKey(), message.id(), mentions.contains(recipient.getValue().toLowerCase(Locale.ROOT)), Timestamp.from(Instant.now()));
    }
    private UUID messageConversation(UUID message) {
        return db.queryForObject("SELECT conversation_id FROM chat_messages WHERE id=?", UUID.class, message);
    }
    @Transactional(readOnly = true)
    public Page feed(String username, Long after) {
        UUID user = users.findByUsername(username).filter(u -> u.isEnabled()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
        long watermark = position();
        if (after == null) return new Page(List.of(), watermark, false, false);
        if (after < 0 || after > watermark) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification cursor.");
        return page(user, after, watermark);
    }
    public Page page(UUID user, long after, long watermark) {
        var rows = db.query("SELECT n.position,n.user_id,n.mentioned,m.id,m.sequence,m.body,m.conversation_id,"
                + "sender.display_name AS sender,c.type,c.name AS group_name,f.original_name "
                + "FROM chat_notifications n JOIN chat_messages m ON m.id=n.message_id "
                + "JOIN chat_conversations c ON c.id=m.conversation_id "
                + "JOIN chat_members cm ON cm.conversation_id=c.id AND cm.user_id=n.user_id AND cm.left_at IS NULL "
                + "JOIN app_users sender ON sender.id=m.sender_id LEFT JOIN chat_files f ON f.id=m.file_id "
                + "WHERE n.user_id=? AND n.position>? AND n.position<=? AND m.deleted_at IS NULL "
                + "AND m.sequence>cm.history_from_sequence AND (n.mentioned=true OR m.sequence>cm.last_read_sequence) "
                + "AND (cm.muted=false OR n.mentioned=true) AND NOT EXISTS "
                + "(SELECT 1 FROM chat_message_hidden h WHERE h.message_id=m.id AND h.user_id=n.user_id) "
                + "ORDER BY n.position LIMIT 51", (rs, count) -> {
                    boolean group = "GROUP".equals(rs.getString("type"));
                    boolean mentioned = rs.getBoolean("mentioned");
                    String name = group ? rs.getString("group_name") : rs.getString("sender");
                    String text = rs.getString("body");
                    if (text.isBlank()) text = rs.getString("original_name") == null ? "New message" : "Attachment: " + rs.getString("original_name");
                    if (text.length() > 180) text = text.substring(0, 180) + "…";
                    return new Item(rs.getLong("position"), user, rs.getObject("conversation_id", UUID.class), rs.getObject("id", UUID.class),
                        rs.getLong("sequence"), mentioned ? "You have been mentioned in " + name : name,
                        group ? rs.getString("sender") + ": " + text : text, mentioned);
                }, user, after, watermark);
        boolean more = rows.size() > 50;
        var page = List.copyOf(rows.subList(0, Math.min(rows.size(), 50)));
        boolean pending = !more && postgres && db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM chat_notifications WHERE user_id=? AND position>?)", Boolean.class, user, watermark);
        return new Page(page, more ? page.getLast().position() : watermark, more, pending);
    }
}
