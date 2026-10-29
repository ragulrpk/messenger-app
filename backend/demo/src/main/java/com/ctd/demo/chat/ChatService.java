package com.ctd.demo.chat;

import com.ctd.demo.user.UserRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.ApplicationEventPublisher;

/** Enforces chat access and stores direct conversations and messages. */
@Service
@Transactional
public class ChatService {
    private final JdbcTemplate db;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final boolean postgres;
    private static final int PAGE = 50;
    /** Receives database access for messages and the user account store. */
    public ChatService(JdbcTemplate db, UserRepository users, ApplicationEventPublisher events) {
        this.db = db;
        this.users = users;
        this.events = events;
        this.postgres = Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection ->
                "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }
    public record Person(UUID id, String username, String name) { }
    public record Message(UUID id, long sequence, UUID senderId, String senderName, UUID clientId,
                          String text, Instant sentAt, Attachment attachment) { }
    /** File details shown with a message; available turns false once the file expires. */
    public record Attachment(UUID id, String name, String contentType, long size, boolean available) { }
    /** A file already written to storage, waiting to be attached to a message. */
    public record NewFile(String storageKey, String name, String contentType, long size, String sha256, Instant expiresAt) { }
    /** A stored message and whether this call created it (false for an idempotent retry). */
    public record Sent(Message message, boolean created) { }
    /** The stored file a member may download. */
    public record Download(String storageKey, String name, String contentType, long size) { }
    public record Conversation(UUID id, UUID targetId, String name, Instant updatedAt, Message lastMessage) { }
    public record ConversationPage(List<Conversation> conversations, String nextCursor) { }
    public record MessagePage(List<Message> messages, boolean hasMore) { }

    /** Resolves an enabled session user to its account ID. */
    private UUID actor(String username) {
        return users.findByUsername(username).filter(u -> u.isEnabled())
                .orElseThrow(() -> new BadCredentialsException("Invalid session")).getId();
    }
    /** Rejects access unless the user belongs to the conversation. */
    private void member(UUID user, UUID conversation) {
        if (db.queryForObject("SELECT count(*) FROM chat_members WHERE user_id=? AND conversation_id=?",
                Integer.class, user, conversation) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
        }
    }
    /** Converts a joined message row into the API message model. */
    private Message message(ResultSet rs, int row) throws SQLException {
        return new Message(rs.getObject("id", UUID.class), rs.getLong("sequence"),
                rs.getObject("sender_id", UUID.class), rs.getString("display_name"),
                rs.getObject("client_id", UUID.class), rs.getString("body"), rs.getTimestamp("sent_at").toInstant(),
                attachment(rs));
    }
    private Message previewMessage(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("message_id", UUID.class);
        if (id == null) return null;
        return new Message(id, rs.getLong("message_sequence"),
                rs.getObject("message_sender_id", UUID.class), rs.getString("message_sender_name"),
                rs.getObject("message_client_id", UUID.class), rs.getString("message_body"),
                rs.getTimestamp("message_sent_at").toInstant(), attachment(rs));
    }
    /** Reads the FILE_COLUMNS of a joined attachment, or null for a text-only message. */
    private static Attachment attachment(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("file_ref", UUID.class);
        if (id == null) return null;
        boolean available = rs.getTimestamp("file_deleted_at") == null
                && rs.getTimestamp("file_expires_at").toInstant().isAfter(Instant.now());
        return new Attachment(id, rs.getString("file_name"), rs.getString("file_type"), rs.getLong("file_size"), available);
    }
    private static String fileColumns(String alias) {
        return alias + ".id AS file_ref," + alias + ".original_name AS file_name," + alias + ".content_type AS file_type,"
                + alias + ".size_bytes AS file_size," + alias + ".expires_at AS file_expires_at,"
                + alias + ".deleted_at AS file_deleted_at";
    }
    private static final String MESSAGES = "SELECT m.*, u.display_name," + fileColumns("f") + " FROM chat_messages m "
            + "JOIN app_users u ON u.id=m.sender_id LEFT JOIN chat_files f ON f.id=m.file_id ";
    private static final String CONVERSATIONS = "SELECT c.id,c.updated_at,target.id AS target_id,target.display_name," +
            "lm.id AS message_id,lm.sequence AS message_sequence,lm.sender_id AS message_sender_id," +
            "sender.display_name AS message_sender_name,lm.client_id AS message_client_id," +
            "lm.body AS message_body,lm.sent_at AS message_sent_at," + fileColumns("lf") + " " +
            "FROM chat_conversations c " +
            "JOIN chat_members mine ON mine.conversation_id=c.id AND mine.user_id=? " +
            "JOIN chat_members other ON other.conversation_id=c.id AND other.user_id<>? " +
            "JOIN app_users target ON target.id=other.user_id " +
            "LEFT JOIN chat_messages lm ON lm.conversation_id=c.id AND lm.sequence=(" +
            "SELECT MAX(last_message.sequence) FROM chat_messages last_message WHERE last_message.conversation_id=c.id) " +
            "LEFT JOIN app_users sender ON sender.id=lm.sender_id " +
            "LEFT JOIN chat_files lf ON lf.id=lm.file_id ";

    /** Finds enabled users matching a literal, case-insensitive search term. */
    @Transactional(readOnly = true)
    public List<Person> directory(String username, String query) {
        UUID user = actor(username);
        String term = query.trim().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) return List.of();
        if (term.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search is too long.");
        // Escape SQL LIKE metacharacters so user input is always a literal search.
        String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return db.query("SELECT id,username,display_name FROM app_users WHERE enabled=true AND id<>? "
                + "AND (lower(display_name) LIKE ? ESCAPE '!' OR username LIKE ? ESCAPE '!') ORDER BY username LIMIT 50",
                (rs, n) -> new Person(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name")),
                user, pattern, pattern);
    }
    /** Lists the caller's conversations in recent-update order. */
    @Transactional(readOnly = true)
    public ConversationPage conversations(String username, String cursor) {
        UUID user = actor(username);
        var params = new ArrayList<Object>(List.of(user, user));
        String after = "";
        if (cursor != null && !cursor.isBlank()) {
            ConversationCursor decoded = decodeCursor(cursor);
            after = "AND (c.updated_at<? OR (c.updated_at=? AND c.id<?)) ";
            params.add(Timestamp.from(decoded.updatedAt()));
            params.add(Timestamp.from(decoded.updatedAt()));
            params.add(decoded.id());
        }
        params.add(PAGE + 1);
        var found = db.query(CONVERSATIONS + "WHERE 1=1 " + after
                        + "ORDER BY c.updated_at DESC,c.id DESC LIMIT ?",
                (rs, n) -> new Conversation(rs.getObject("id", UUID.class),
                        rs.getObject("target_id", UUID.class), rs.getString("display_name"),
                        rs.getTimestamp("updated_at").toInstant(), previewMessage(rs)), params.toArray());
        boolean more = found.size() > PAGE;
        var page = List.copyOf(found.subList(0, Math.min(PAGE, found.size())));
        String next = more ? encodeCursor(page.getLast()) : null;
        return new ConversationPage(page, next);
    }
    /** Builds a conversation preview with its other member and latest message. */
    private Conversation view(UUID user, UUID id) {
        return db.queryForObject(CONVERSATIONS + "WHERE c.id=?",
                (rs, n) -> new Conversation(rs.getObject("id", UUID.class),
                        rs.getObject("target_id", UUID.class), rs.getString("display_name"),
                        rs.getTimestamp("updated_at").toInstant(), previewMessage(rs)), user, user, id);
    }

    private record ConversationCursor(Instant updatedAt, UUID id) { }

    private String encodeCursor(Conversation conversation) {
        String value = conversation.updatedAt() + "|" + conversation.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ConversationCursor decodeCursor(String cursor) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
            int separator = value.lastIndexOf('|');
            return new ConversationCursor(Instant.parse(value.substring(0, separator)),
                    UUID.fromString(value.substring(separator + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid conversation cursor.");
        }
    }
    /** Finds or safely creates the unique direct conversation for two users. */
    public Conversation direct(String username, UUID target) {
        UUID user = actor(username);
        if (user.equals(target)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another person.");
        users.findById(target).filter(u -> u.isEnabled())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found."));
        var pair = new ArrayList<>(List.of(user.toString(), target.toString()));
        Collections.sort(pair);
        // The same first-user lock is used by both callers, making direct creation race-safe.
        db.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE", UUID.class, UUID.fromString(pair.getFirst()));
        String key = String.join(":", pair);
        var existing = db.query("SELECT id FROM chat_conversations WHERE direct_key=?", (rs, n) -> rs.getObject(1, UUID.class), key);
        UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst();
        if (existing.isEmpty()) {
            Timestamp now = Timestamp.from(Instant.now());
            db.update("INSERT INTO chat_conversations(id,direct_key,created_at,updated_at) VALUES (?,?,?,?)", id, key, now, now);
            db.update("INSERT INTO chat_members(conversation_id,user_id) VALUES (?,?),(?,?)", id, user, id, target);
            changed(id);
        }
        return view(user, id);
    }
    /** Loads older or newer messages after checking membership and cursors. */
    @Transactional(readOnly = true)
    public MessagePage history(String username, UUID id, Long before, Long after) {
        UUID user = actor(username);
        member(user, id);
        if ((before != null && after != null) || (before != null && before < 1) || (after != null && after < 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid message cursor.");
        var params = new ArrayList<Object>(List.of(id));
        String cursor = "";
        if (before != null) { cursor = " AND m.sequence<?"; params.add(before); }
        if (after != null) { cursor = " AND m.sequence>?"; params.add(after); }
        var found = db.query(MESSAGES + "WHERE m.conversation_id=?" + cursor
                + " ORDER BY m.sequence " + (after == null ? "DESC" : "ASC") + " LIMIT 51", this::message, params.toArray());
        boolean more = found.size() > PAGE;
        var page = new ArrayList<>(found.subList(0, Math.min(PAGE, found.size())));
        if (after == null) Collections.reverse(page);
        return new MessagePage(page, more);
    }
    /** Stores a message once per client ID and updates conversation recency. */
    public Message send(String username, UUID id, UUID clientId, String text) {
        UUID user = actor(username);
        member(user, id);
        String body = text.strip();
        if (body.isBlank() || body.length() > 4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must contain 1–4000 characters.");
        return store(user, id, clientId, body, null).message();
    }
    /** Rejects a non-member before an upload is written to storage. */
    @Transactional(readOnly = true)
    public void requireMember(String username, UUID conversation) {
        member(actor(username), conversation);
    }
    /** Stores a message carrying an already written file and an optional caption. */
    public Sent sendFile(String username, UUID id, UUID clientId, String caption, NewFile file) {
        UUID user = actor(username);
        member(user, id);
        String body = caption.strip();
        if (body.length() > 4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Caption must contain at most 4000 characters.");
        return store(user, id, clientId, body, file);
    }
    /** Inserts a message once per client ID; a retry returns the original if it carries the same content. */
    private Sent store(UUID user, UUID id, UUID clientId, String body, NewFile file) {
        // 2026-09-23: Lock BEFORE sequence allocation so reconnect/catch-up cannot skip late commits.
        db.queryForObject("SELECT id FROM chat_conversations WHERE id=? FOR UPDATE", UUID.class, id);
        var existing = db.query(MESSAGES + "WHERE m.conversation_id=? AND m.sender_id=? AND m.client_id=?", this::message, id, user, clientId);
        if (!existing.isEmpty()) return new Sent(sameRetry(existing.getFirst(), body, file), false);
        UUID messageId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        UUID fileId = null;
        if (file != null) {
            fileId = UUID.randomUUID();
            db.update("INSERT INTO chat_files(id,conversation_id,uploader_id,original_name,content_type,size_bytes,sha256,"
                            + "storage_key,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    fileId, id, user, file.name(), file.contentType(), file.size(), file.sha256(), file.storageKey(),
                    now, Timestamp.from(file.expiresAt()));
        }
        int inserted;
        if (postgres) {
            inserted = db.update("INSERT INTO chat_messages(id,conversation_id,sender_id,client_id,body,sent_at,file_id) "
                            + "VALUES (?,?,?,?,?,?,?) ON CONFLICT (conversation_id,sender_id,client_id) DO NOTHING",
                    messageId, id, user, clientId, body, now, fileId);
        } else {
            // H2 lacks ON CONFLICT; the common conversation lock already serializes duplicate checks.
            inserted = db.update("INSERT INTO chat_messages(id,conversation_id,sender_id,client_id,body,sent_at,file_id) VALUES (?,?,?,?,?,?,?)",
                    messageId, id, user, clientId, body, now, fileId);
        }
        if (inserted == 1) {
            db.update("UPDATE chat_conversations SET updated_at=CASE WHEN updated_at<? THEN ? ELSE updated_at END WHERE id=?",
                    now, now, id);
            changed(id);
            return new Sent(db.queryForObject(MESSAGES + "WHERE m.id=?", this::message, messageId), true);
        }
        if (fileId != null) db.update("DELETE FROM chat_files WHERE id=?", fileId);
        Message retry = db.queryForObject(MESSAGES + "WHERE m.conversation_id=? AND m.sender_id=? AND m.client_id=?",
                this::message, id, user, clientId);
        return new Sent(sameRetry(retry, body, file), false);
    }
    /** Accepts a retry only when its text and file match the message stored for that client ID. */
    private Message sameRetry(Message stored, String body, NewFile file) {
        boolean same = stored.text().equals(body) && (file == null) == (stored.attachment() == null)
                && (file == null || file.sha256().equals(db.queryForObject(
                        "SELECT sha256 FROM chat_files WHERE id=?", String.class, stored.attachment().id())));
        if (!same) throw new ResponseStatusException(HttpStatus.CONFLICT, "Retry identifier was already used for different content.");
        return stored;
    }
    /** Finds a file the user may download through conversation membership. */
    @Transactional(readOnly = true)
    public Download download(String username, UUID fileId) {
        UUID user = actor(username);
        var found = db.query("SELECT f.storage_key,f.original_name,f.content_type,f.size_bytes,f.expires_at,f.deleted_at "
                        + "FROM chat_files f JOIN chat_members m ON m.conversation_id=f.conversation_id AND m.user_id=? WHERE f.id=?",
                (rs, n) -> {
                    if (rs.getTimestamp("deleted_at") != null || !rs.getTimestamp("expires_at").toInstant().isAfter(Instant.now()))
                        throw new ResponseStatusException(HttpStatus.GONE, "File has expired.");
                    return new Download(rs.getString("storage_key"), rs.getString("original_name"),
                            rs.getString("content_type"), rs.getLong("size_bytes"));
                }, user, fileId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found.");
        return found.getFirst();
    }

    public void typing(String username, UUID conversation, boolean active) {
        UUID user = actor(username);
        member(user, conversation);
        events.publishEvent(new ChatTyping(db.queryForList(
                "SELECT u.username FROM chat_members m JOIN app_users u ON u.id=m.user_id "
                        + "WHERE m.conversation_id=? AND m.user_id<>? AND u.enabled=true",
                String.class, conversation, user), conversation, active));
    }

    /** 2026-09-23: Resolve recipients from membership, then notify only after the transaction commits. */
    private void changed(UUID conversation) {
        events.publishEvent(new ChatChanged(db.queryForList(
                "SELECT u.username FROM chat_members m JOIN app_users u ON u.id=m.user_id WHERE m.conversation_id=?",
                String.class, conversation)));
    }
}
