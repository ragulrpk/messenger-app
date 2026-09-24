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
                          String text, Instant sentAt) { }
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
                rs.getObject("client_id", UUID.class), rs.getString("body"), rs.getTimestamp("sent_at").toInstant());
    }
    private Message previewMessage(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("message_id", UUID.class);
        if (id == null) return null;
        return new Message(id, rs.getLong("message_sequence"),
                rs.getObject("message_sender_id", UUID.class), rs.getString("message_sender_name"),
                rs.getObject("message_client_id", UUID.class), rs.getString("message_body"),
                rs.getTimestamp("message_sent_at").toInstant());
    }
    private static final String MESSAGES = "SELECT m.*, u.display_name FROM chat_messages m JOIN app_users u ON u.id=m.sender_id ";
    private static final String CONVERSATIONS = "SELECT c.id,c.updated_at,target.id AS target_id,target.display_name," +
            "lm.id AS message_id,lm.sequence AS message_sequence,lm.sender_id AS message_sender_id," +
            "sender.display_name AS message_sender_name,lm.client_id AS message_client_id," +
            "lm.body AS message_body,lm.sent_at AS message_sent_at " +
            "FROM chat_conversations c " +
            "JOIN chat_members mine ON mine.conversation_id=c.id AND mine.user_id=? " +
            "JOIN chat_members other ON other.conversation_id=c.id AND other.user_id<>? " +
            "JOIN app_users target ON target.id=other.user_id " +
            "LEFT JOIN chat_messages lm ON lm.conversation_id=c.id AND lm.sequence=(" +
            "SELECT MAX(last_message.sequence) FROM chat_messages last_message WHERE last_message.conversation_id=c.id) " +
            "LEFT JOIN app_users sender ON sender.id=lm.sender_id ";

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
        // 2026-09-23: Lock BEFORE sequence allocation so reconnect/catch-up cannot skip late commits.
        db.queryForObject("SELECT id FROM chat_conversations WHERE id=? FOR UPDATE", UUID.class, id);
        var existing = db.query(MESSAGES + "WHERE m.conversation_id=? AND m.sender_id=? AND m.client_id=?", this::message, id, user, clientId);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().text().equals(body)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Retry identifier was already used for different text.");
            return existing.getFirst();
        }
        UUID messageId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        int inserted;
        if (postgres) {
            inserted = db.update("INSERT INTO chat_messages(id,conversation_id,sender_id,client_id,body,sent_at) "
                            + "VALUES (?,?,?,?,?,?) ON CONFLICT (conversation_id,sender_id,client_id) DO NOTHING",
                    messageId, id, user, clientId, body, now);
        } else {
            // H2 lacks ON CONFLICT; the common conversation lock already serializes duplicate checks.
            inserted = db.update("INSERT INTO chat_messages(id,conversation_id,sender_id,client_id,body,sent_at) VALUES (?,?,?,?,?,?)",
                    messageId, id, user, clientId, body, now);
        }
        if (inserted == 1) {
            db.update("UPDATE chat_conversations SET updated_at=CASE WHEN updated_at<? THEN ? ELSE updated_at END WHERE id=?",
                    now, now, id);
            changed(id);
            return db.queryForObject(MESSAGES + "WHERE m.id=?", this::message, messageId);
        }
        Message retry = db.queryForObject(MESSAGES + "WHERE m.conversation_id=? AND m.sender_id=? AND m.client_id=?",
                this::message, id, user, clientId);
        if (!retry.text().equals(body)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Retry identifier was already used for different text.");
        return retry;
    }

    /** 2026-09-23: Resolve recipients from membership, then notify only after the transaction commits. */
    private void changed(UUID conversation) {
        events.publishEvent(new ChatChanged(db.queryForList(
                "SELECT u.username FROM chat_members m JOIN app_users u ON u.id=m.user_id WHERE m.conversation_id=?",
                String.class, conversation)));
    }
}
