package com.ctd.demo.chat;

import com.ctd.demo.chat.ChatService.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;

/** JDBC persistence for conversations, membership, and messages. Uses the caller's transaction. */
@Repository
public class ChatRepository {
    private final JdbcTemplate db;
    private final boolean postgres;

    public ChatRepository(JdbcTemplate db) {
        this.db = db;
        this.postgres = Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection ->
                "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    /** Converts a joined message row into the API message model; reactions are added separately. */
    private Message message(ResultSet rs, int row) throws SQLException {
        boolean deleted = rs.getTimestamp("deleted_at") != null;
        Attachment first = deleted ? null : attachment(rs);
        return new Message(rs.getObject("id", UUID.class), rs.getLong("sequence"),
                rs.getObject("sender_id", UUID.class), rs.getString("display_name"),
                rs.getObject("client_id", UUID.class), deleted ? "" : rs.getString("body"),
                rs.getTimestamp("sent_at").toInstant(), first, first == null ? List.of() : List.of(first),
                rs.getTimestamp("edited_at") != null, deleted, rs.getBoolean("forwarded"), reply(rs), List.of(), List.of());
    }
    private Message previewMessage(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("message_id", UUID.class);
        if (id == null) return null;
        boolean deleted = rs.getTimestamp("message_deleted_at") != null;
        // The conversation list previews only the first file.
        Attachment first = deleted ? null : attachment(rs);
        return new Message(id, rs.getLong("message_sequence"),
                rs.getObject("message_sender_id", UUID.class), rs.getString("message_sender_name"),
                rs.getObject("message_client_id", UUID.class), deleted ? "" : rs.getString("message_body"),
                rs.getTimestamp("message_sent_at").toInstant(), first, first == null ? List.of() : List.of(first),
                rs.getTimestamp("message_edited_at") != null, deleted, rs.getBoolean("message_forwarded"), null, List.of(),
                List.of());
    }
    /** Reads the quoted message of a reply; its text is shortened and hidden once it is deleted. */
    private static Reply reply(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("reply_ref", UUID.class);
        if (id == null) return null;
        boolean deleted = rs.getTimestamp("reply_deleted_at") != null;
        String text = deleted ? "" : rs.getString("reply_body");
        return new Reply(id, rs.getString("reply_sender"), text.length() > 200 ? text.substring(0, 200) : text,
                deleted ? null : rs.getString("reply_file_name"), deleted, rs.getLong("reply_sequence"));
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
    private static final String MESSAGES = "SELECT m.*, u.display_name," + fileColumns("f") + ","
            + "rm.id AS reply_ref,rm.sequence AS reply_sequence,ru.display_name AS reply_sender,rm.body AS reply_body,"
            + "rm.deleted_at AS reply_deleted_at,rf.original_name AS reply_file_name FROM chat_messages m "
            + "JOIN app_users u ON u.id=m.sender_id LEFT JOIN chat_files f ON f.id=m.file_id "
            + "LEFT JOIN chat_messages rm ON rm.id=m.reply_to_id LEFT JOIN app_users ru ON ru.id=rm.sender_id "
            + "LEFT JOIN chat_files rf ON rf.id=rm.file_id ";
    /** Leaves out messages the user deleted for themselves and messages from before they joined;
     *  takes the user as two parameters. */
    private static final String VISIBLE = " AND NOT EXISTS (SELECT 1 FROM chat_message_hidden h "
            + "WHERE h.message_id=m.id AND h.user_id=?) AND m.sequence>(SELECT hm.history_from_sequence "
            + "FROM chat_members hm WHERE hm.conversation_id=m.conversation_id AND hm.user_id=?)";
    /** Maps a CONVERSATIONS row; a manual unread marker shows as at least one unread message.
     *  Groups have no target person, so no status or note. */
    private Conversation conversation(ResultSet rs, int row) throws SQLException {
        int unread = rs.getInt("unread_count");
        UUID target = rs.getObject("target_id", UUID.class);
        return new Conversation(rs.getObject("id", UUID.class),
                target, rs.getString("display_name"),
                rs.getTimestamp("updated_at").toInstant(), previewMessage(rs),
                rs.getBoolean("pinned"), rs.getBoolean("muted"), rs.getBoolean("hidden"),
                rs.getBoolean("marked_unread") ? Math.max(1, unread) : unread,
                target == null ? null : rs.getString("target_status"), target == null ? null : rs.getString("target_note"),
                rs.getLong("target_read"), rs.getLong("target_delivered"),
                rs.getString("type"), rs.getString("role"), rs.getInt("member_count"), rs.getBoolean("admins_only"), rs.getLong("history_from_sequence"),
                rs.getTimestamp("archived_at") != null);
    }
    /** Read and delivered positions of the other side: the other person's row in a direct chat, and the
     *  lowest among the other current members in a group (seen only once everyone has read it). The
     *  (conversation_id, position) indexes let the group lookup stop at the first qualifying member. */
    private static String othersLowest(String column) {
        return "CASE WHEN c.type='DIRECT' THEN COALESCE(other." + column + ",0) ELSE "
                + "(SELECT COALESCE(MIN(o." + column + "),0) FROM chat_members o WHERE o.conversation_id=c.id "
                + "AND o.user_id<>mine.user_id AND o.left_at IS NULL) END";
    }
    private static final String CONVERSATIONS = "SELECT c.id,c.updated_at,c.type,c.admins_only,c.archived_at,mine.role,mine.history_from_sequence,target.id AS target_id," +
            "COALESCE(c.name,target.display_name) AS display_name," +
            "COALESCE(presence.status,'online') AS target_status,COALESCE(presence.note,'') AS target_note,mine.pinned,mine.muted,mine.hidden,mine.marked_unread," +
            othersLowest("last_read_sequence") + " AS target_read," + othersLowest("last_delivered_sequence") + " AS target_delivered," +
            "c.member_count," +
            "(SELECT count(*) FROM chat_messages unseen WHERE unseen.conversation_id=c.id " +
            "AND unseen.sequence>mine.last_read_sequence AND unseen.sender_id<>mine.user_id) AS unread_count," +
            "lm.id AS message_id,lm.sequence AS message_sequence,lm.sender_id AS message_sender_id," +
            "sender.display_name AS message_sender_name,lm.client_id AS message_client_id," +
            "lm.body AS message_body,lm.sent_at AS message_sent_at,lm.edited_at AS message_edited_at," +
            "lm.deleted_at AS message_deleted_at,lm.forwarded AS message_forwarded," + fileColumns("lf") + " " +
            "FROM chat_conversations c " +
            "JOIN chat_members mine ON mine.conversation_id=c.id AND mine.user_id=? AND mine.left_at IS NULL " +
            "LEFT JOIN chat_members other ON other.conversation_id=c.id AND other.user_id<>? AND c.type='DIRECT' " +
            "LEFT JOIN app_users target ON target.id=other.user_id " +
            "LEFT JOIN user_presence presence ON presence.user_id=target.id " +
            // The preview is the newest message this member can still see: after their history start and not
            // deleted for them. ORDER BY ... LIMIT 1, not MAX: with the NOT EXISTS, PostgreSQL plans MAX as an
            // anti-join over the whole history, while this walks the history index newest-first and stops at
            // the first shown message, usually the first row.
            "LEFT JOIN chat_messages lm ON lm.conversation_id=c.id AND lm.sequence=(" +
            "SELECT shown.sequence FROM chat_messages shown WHERE shown.conversation_id=c.id " +
            "AND shown.sequence>mine.history_from_sequence AND NOT EXISTS (SELECT 1 FROM chat_message_hidden h " +
            "WHERE h.message_id=shown.id AND h.user_id=mine.user_id) ORDER BY shown.sequence DESC LIMIT 1) " +
            "LEFT JOIN app_users sender ON sender.id=lm.sender_id " +
            "LEFT JOIN chat_files lf ON lf.id=lm.file_id ";


    public boolean isMember(UUID user, UUID conversation) {
        return db.queryForObject("SELECT count(*) FROM chat_members WHERE user_id=? AND conversation_id=? AND left_at IS NULL",
                Integer.class, user, conversation) != 0;
    }

    public String findType(UUID conversation) {
        return db.queryForObject("SELECT type FROM chat_conversations WHERE id=?", String.class, conversation);
    }

    /** A current member's role, or empty when the user is not (or no longer) a member. */
    public Optional<String> findRole(UUID conversation, UUID user) {
        return db.queryForList("SELECT role FROM chat_members WHERE conversation_id=? AND user_id=? AND left_at IS NULL",
                String.class, conversation, user).stream().findFirst();
    }

    /** Current members, admins first, then by name. */
    public List<Member> findMembers(UUID conversation) {
        return db.query("SELECT u.id,u.username,u.display_name,m.role FROM chat_members m JOIN app_users u ON u.id=m.user_id "
                        + "WHERE m.conversation_id=? AND m.left_at IS NULL ORDER BY CASE WHEN m.role='admin' THEN 0 ELSE 1 END,"
                        + "lower(u.display_name),u.id",
                (rs, n) -> new Member(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("role")), conversation);
    }

    public void createGroup(UUID id, String name, UUID creator, Collection<UUID> members, boolean adminsOnly,
            Instant createdAt) {
        Timestamp now = Timestamp.from(createdAt);
        db.update("INSERT INTO chat_conversations(id,type,name,created_by,admins_only,created_at,updated_at) "
                        + "VALUES (?,'GROUP',?,?,?,?,?)", id, name, creator, adminsOnly, now, now);
        db.update("INSERT INTO chat_members(conversation_id,user_id,role,joined_at) VALUES (?,?,'admin',?)", id, creator, now);
        for (UUID member : members) addMember(id, member, createdAt);
        refreshMemberCount(id);
    }

    /** Adds a member, or brings back one who left; earlier history counts as already read. */
    public void addMember(UUID conversation, UUID user, Instant joinedAt) {
        Timestamp now = Timestamp.from(joinedAt);
        long newest = db.queryForObject("SELECT COALESCE(MAX(sequence),0) FROM chat_messages WHERE conversation_id=?",
                Long.class, conversation);
        // A newcomer, or someone coming back, sees only what is said from now on.
        int rejoined = db.update("UPDATE chat_members SET left_at=NULL,role='member',joined_at=?,hidden=false,pinned=false,"
                        + "marked_unread=false,last_read_sequence=?,last_delivered_sequence=?,history_from_sequence=? "
                        + "WHERE conversation_id=? AND user_id=?",
                now, newest, newest, newest, conversation, user);
        if (rejoined == 0)
            db.update("INSERT INTO chat_members(conversation_id,user_id,role,joined_at,last_read_sequence,"
                    + "last_delivered_sequence,history_from_sequence) VALUES (?,?,'member',?,?,?,?)",
                    conversation, user, now, newest, newest, newest);
    }

    /** Ends a membership; the row stays because the member's messages reference it. */
    public void removeMember(UUID conversation, UUID user, Instant leftAt) {
        db.update("UPDATE chat_members SET left_at=?,role='member' WHERE conversation_id=? AND user_id=? AND left_at IS NULL",
                Timestamp.from(leftAt), conversation, user);
    }

    public void setRole(UUID conversation, UUID user, String role) {
        db.update("UPDATE chat_members SET role=? WHERE conversation_id=? AND user_id=? AND left_at IS NULL", role, conversation, user);
    }

    public int countAdmins(UUID conversation) {
        return db.queryForObject("SELECT count(*) FROM chat_members WHERE conversation_id=? AND role='admin' AND left_at IS NULL",
                Integer.class, conversation);
    }

    /** The longest-standing current member, who becomes admin when the last admin leaves. */
    public Optional<UUID> findEarliestMember(UUID conversation) {
        return db.queryForList("SELECT user_id FROM chat_members WHERE conversation_id=? AND left_at IS NULL "
                + "ORDER BY joined_at, user_id LIMIT 1", UUID.class, conversation).stream().findFirst();
    }

    public boolean isArchived(UUID conversation) {
        return db.queryForObject("SELECT archived_at IS NOT NULL FROM chat_conversations WHERE id=?", Boolean.class, conversation);
    }

    /** Archives a group (null restores it). Archived groups stay readable; nobody can post. */
    public void setArchived(UUID conversation, Instant at) {
        db.update("UPDATE chat_conversations SET archived_at=? WHERE id=?", at == null ? null : Timestamp.from(at), conversation);
    }

    /** A group as administrators see it, whether or not they belong to it. */
    public record GroupSummary(UUID id, String name, int memberCount, boolean adminsOnly, boolean archived, Instant updatedAt) { }

    private static GroupSummary groupSummary(ResultSet rs, int row) throws SQLException {
        return new GroupSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("member_count"),
                rs.getBoolean("admins_only"), rs.getTimestamp("archived_at") != null, rs.getTimestamp("updated_at").toInstant());
    }

    /** Every group matching a name search, most recently active first; limit+1 rows tell the caller if more exist. */
    public List<GroupSummary> findGroups(String term, int offset, int limit) {
        String literal = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return db.query("SELECT id,name,member_count,admins_only,archived_at,updated_at FROM chat_conversations "
                + "WHERE type='GROUP' AND LOWER(name) LIKE ? ESCAPE '!' ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",
                ChatRepository::groupSummary, literal, limit, offset);
    }

    public Optional<GroupSummary> findGroup(UUID id) {
        return db.query("SELECT id,name,member_count,admins_only,archived_at,updated_at FROM chat_conversations "
                + "WHERE type='GROUP' AND id=?", ChatRepository::groupSummary, id).stream().findFirst();
    }

    public boolean isAdminsOnly(UUID conversation) {
        return db.queryForObject("SELECT admins_only FROM chat_conversations WHERE id=?", Boolean.class, conversation);
    }

    public void setAdminsOnly(UUID conversation, boolean adminsOnly) {
        db.update("UPDATE chat_conversations SET admins_only=? WHERE id=?", adminsOnly, conversation);
    }

    public void rename(UUID conversation, String name) {
        db.update("UPDATE chat_conversations SET name=? WHERE id=?", name, conversation);
    }

    public List<Conversation> findConversations(UUID user, Instant updatedAt, UUID cursorId, int limit) {
        var params = new ArrayList<Object>(List.of(user, user));
        String after = "";
        if (updatedAt != null) {
            after = "AND (c.updated_at<? OR (c.updated_at=? AND c.id<?)) ";
            params.add(Timestamp.from(updatedAt));
            params.add(Timestamp.from(updatedAt));
            params.add(cursorId);
        }
        params.add(limit);
        return db.query(CONVERSATIONS + "WHERE 1=1 " + after
                        + "ORDER BY c.updated_at DESC,c.id DESC LIMIT ?",
                this::conversation, params.toArray());
    }

    /** Searches all groups the caller currently belongs to, including hidden groups. */
    public List<Conversation> searchGroups(UUID user, String term) {
        String literal = term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return db.query(CONVERSATIONS + "WHERE c.type='GROUP' AND LOWER(c.name) LIKE ? ESCAPE '!' "
                + "ORDER BY c.updated_at DESC,c.id DESC LIMIT 50", this::conversation, user, user, "%" + literal + "%");
    }

    public Conversation findConversation(UUID user, UUID id) {
        return db.queryForObject(CONVERSATIONS + "WHERE c.id=?", this::conversation, user, user, id);
    }

    /** Applies only the preferences that were supplied; null leaves a value unchanged. */
    public void updatePreferences(UUID user, UUID conversation, Boolean pinned, Boolean muted, Boolean hidden,
            Boolean unread) {
        var sets = new ArrayList<String>();
        var params = new ArrayList<Object>();
        if (pinned != null) { sets.add("pinned=?"); params.add(pinned); }
        if (muted != null) { sets.add("muted=?"); params.add(muted); }
        if (hidden != null) { sets.add("hidden=?"); params.add(hidden); }
        if (unread != null) { sets.add("marked_unread=?"); params.add(unread); }
        if (sets.isEmpty()) return;
        params.add(conversation);
        params.add(user);
        db.update("UPDATE chat_members SET " + String.join(",", sets) + " WHERE conversation_id=? AND user_id=?",
                params.toArray());
    }

    /** Moves the read position forward to a message that exists, never backward, and clears a manual marker.
     *  A read message has also been delivered. */
    public void clearHistory(UUID user, UUID id) {
        long latest = db.queryForObject("SELECT COALESCE(MAX(sequence),0) FROM chat_messages WHERE conversation_id=?", Long.class, id);
        db.update("UPDATE chat_members SET history_from_sequence=GREATEST(history_from_sequence,?),"
                + "last_read_sequence=GREATEST(last_read_sequence,?),last_delivered_sequence=GREATEST(last_delivered_sequence,?),"
                + "marked_unread=false WHERE conversation_id=? AND user_id=?", latest, latest, latest, id, user);
    }

    public void markRead(UUID user, UUID conversation, long sequence) {
        String target = "LEAST(?, (SELECT COALESCE(MAX(m.sequence),0) FROM chat_messages m WHERE m.conversation_id=?))";
        db.update("UPDATE chat_members SET marked_unread=false, last_read_sequence=GREATEST(last_read_sequence, " + target
                        + "), last_delivered_sequence=GREATEST(last_delivered_sequence, " + target + ") "
                        + "WHERE conversation_id=? AND user_id=?",
                sequence, conversation, sequence, conversation, conversation, user);
    }

    /** A conversation holding messages from others newer than what has reached the user. */
    public record Undelivered(UUID conversation, long sequence) { }

    /** For each of the user's conversations, looks only at messages after their delivered position, a short
     *  range on the (conversation_id, sequence) index, rather than at the whole history. */
    public List<Undelivered> findUndelivered(UUID user) {
        return db.query("SELECT conversation_id, newest FROM (SELECT mine.conversation_id,"
                        + "(SELECT MAX(m.sequence) FROM chat_messages m WHERE m.conversation_id=mine.conversation_id "
                        + "AND m.sequence>mine.last_delivered_sequence AND m.sender_id<>mine.user_id) AS newest "
                        + "FROM chat_members mine WHERE mine.user_id=? AND mine.left_at IS NULL) pending "
                        + "WHERE newest IS NOT NULL",
                (rs, n) -> new Undelivered(rs.getObject("conversation_id", UUID.class), rs.getLong("newest")),
                user);
    }

    /** A member's current read and delivered positions. */
    public record Positions(long read, long delivered, long historyFrom) { }

    public Positions findPositions(UUID user, UUID conversation) {
        return db.queryForObject("SELECT last_read_sequence,last_delivered_sequence,history_from_sequence "
                        + "FROM chat_members WHERE conversation_id=? AND user_id=?",
                (rs, n) -> new Positions(rs.getLong(1), rs.getLong(2), rs.getLong(3)), conversation, user);
    }

    /** People other than the user who sent messages in a sequence range: the senders whose receipts change
     *  when the user receives or reads that range. */
    public List<String> findSenderUsernames(UUID conversation, long after, long upTo, UUID user) {
        return db.queryForList("SELECT DISTINCT u.username FROM chat_messages m JOIN app_users u ON u.id=m.sender_id "
                        + "WHERE m.conversation_id=? AND m.sequence>? AND m.sequence<=? AND m.sender_id<>?",
                String.class, conversation, after, upTo, user);
    }

    /** Recounts current members after membership changes; the conversation list reads the stored count. */
    public void refreshMemberCount(UUID conversation) {
        db.update("UPDATE chat_conversations SET member_count=(SELECT count(*) FROM chat_members m "
                + "WHERE m.conversation_id=? AND m.left_at IS NULL) WHERE id=?", conversation, conversation);
    }

    /** Moves the delivered position forward, never backward. */
    public void markDelivered(UUID user, UUID conversation, long sequence) {
        db.update("UPDATE chat_members SET last_delivered_sequence=GREATEST(last_delivered_sequence, ?) "
                + "WHERE conversation_id=? AND user_id=?", sequence, conversation, user);
    }

    /** Shows the conversation again to one member, or to every member when user is null. */
    public void unhide(UUID conversation, UUID user) {
        if (user == null) db.update("UPDATE chat_members SET hidden=false WHERE conversation_id=? AND hidden=true", conversation);
        else db.update("UPDATE chat_members SET hidden=false WHERE conversation_id=? AND user_id=?", conversation, user);
    }

    public List<UUID> findDirect(String key) {
        return db.query("SELECT id FROM chat_conversations WHERE direct_key=?", (rs, n) -> rs.getObject(1, UUID.class), key);
    }

    public void createDirect(UUID id, String key, UUID user, UUID target, Instant createdAt) {
        Timestamp now = Timestamp.from(createdAt);
        db.update("INSERT INTO chat_conversations(id,direct_key,created_at,updated_at,member_count) VALUES (?,?,?,?,2)",
                id, key, now, now);
        db.update("INSERT INTO chat_members(conversation_id,user_id,joined_at) VALUES (?,?,?),(?,?,?)",
                id, user, now, id, target, now);
    }

    public List<Message> findMessages(UUID user, UUID id, Long before, Long after, int limit) {
        var params = new ArrayList<Object>(List.of(id, user, user));
        String cursor = "";
        if (before != null) { cursor = " AND m.sequence<?"; params.add(before); }
        if (after != null) { cursor = " AND m.sequence>?"; params.add(after); }
        params.add(limit);
        return db.query(MESSAGES + "WHERE m.conversation_id=?" + VISIBLE + cursor
                + " ORDER BY m.sequence " + (after == null ? "DESC" : "ASC") + " LIMIT ?", this::message, params.toArray());
    }

    public List<Message> findSharedFiles(UUID user, UUID id, Long before, int limit) {
        var params = new ArrayList<Object>(List.of(id, user, user));
        String cursor = "";
        if (before != null) { cursor = " AND m.sequence<?"; params.add(before); }
        params.add(limit);
        return db.query(MESSAGES + "WHERE m.conversation_id=?" + VISIBLE
                + " AND m.file_id IS NOT NULL AND m.deleted_at IS NULL" + cursor
                + " ORDER BY m.sequence DESC LIMIT ?", this::message, params.toArray());
    }

    public List<Message> searchMessages(UUID user, UUID id, String term, Long before, int limit) {
        String literal = term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        var params = new ArrayList<Object>(List.of(id, user, user, "%" + literal + "%"));
        String cursor = "";
        if (before != null) { cursor = " AND m.sequence<?"; params.add(before); }
        params.add(limit);
        return db.query(MESSAGES + "WHERE m.conversation_id=?" + VISIBLE
                + " AND m.deleted_at IS NULL AND LOWER(m.body) LIKE ? ESCAPE '!'" + cursor
                + " ORDER BY m.sequence DESC LIMIT ?", this::message, params.toArray());
    }

    /** Messages edited, deleted or reacted to after a revision, oldest first. */
    public List<Message> findChanged(UUID user, UUID id, long revision, long through) {
        return db.query(MESSAGES + "WHERE m.conversation_id=?" + VISIBLE + " AND m.revision>? AND m.revision<=? ORDER BY m.revision",
                this::message, id, user, user, revision, through);
    }

    /** Messages the user deleted for themselves after a revision, so their other devices drop them too. */
    public List<UUID> findRemoved(UUID user, UUID id, long revision, long through) {
        return db.queryForList("SELECT h.message_id FROM chat_message_hidden h JOIN chat_messages m ON m.id=h.message_id "
                + "WHERE m.conversation_id=? AND h.user_id=? AND h.revision>? AND h.revision<=? ORDER BY h.revision", UUID.class, id, user, revision, through);
    }

    public long findRevision(UUID id) {
        return db.queryForObject("SELECT revision FROM chat_conversations WHERE id=?", Long.class, id);
    }

    /** Allocates the next change number; the caller must hold the conversation lock. */
    public long nextRevision(UUID id) {
        db.update("UPDATE chat_conversations SET revision=revision+1 WHERE id=?", id);
        return findRevision(id);
    }

    /** The facts needed to authorize an action on a message the user can see. */
    public record MessageRef(UUID conversation, UUID sender, boolean deleted, UUID fileId, String body) { }

    public List<MessageRef> findMessageRef(UUID user, UUID messageId) {
        return db.query("SELECT m.conversation_id,m.sender_id,m.deleted_at,m.file_id,m.body FROM chat_messages m "
                        + "JOIN chat_members cm ON cm.conversation_id=m.conversation_id AND cm.user_id=? AND cm.left_at IS NULL "
                        + "WHERE m.id=?" + VISIBLE,
                (rs, n) -> new MessageRef(rs.getObject("conversation_id", UUID.class), rs.getObject("sender_id", UUID.class),
                        rs.getTimestamp("deleted_at") != null, rs.getObject("file_id", UUID.class), rs.getString("body")),
                user, messageId, user, user);
    }

    public void editMessage(UUID messageId, String body, Instant editedAt, long revision) {
        db.update("UPDATE chat_messages SET body=?,edited_at=?,revision=? WHERE id=?",
                body, Timestamp.from(editedAt), revision, messageId);
    }

    /** Wipes the text and reactions of a message deleted for everyone; any file is expired by the caller. */
    public void deleteMessage(UUID messageId, Instant deletedAt, long revision) {
        db.update("UPDATE chat_messages SET body='',deleted_at=?,revision=? WHERE id=?",
                Timestamp.from(deletedAt), revision, messageId);
        db.update("DELETE FROM chat_reactions WHERE message_id=?", messageId);
    }

    public void hideMessage(UUID user, UUID messageId, long revision) {
        db.update("INSERT INTO chat_message_hidden(message_id,user_id,revision) VALUES (?,?,?)", messageId, user, revision);
    }

    /** Replaces the user's reaction, or removes it when emoji is null. */
    public void setReaction(UUID user, UUID messageId, String emoji, Instant now, long revision) {
        db.update("DELETE FROM chat_reactions WHERE message_id=? AND user_id=?", messageId, user);
        if (emoji != null)
            db.update("INSERT INTO chat_reactions(message_id,user_id,emoji,created_at) VALUES (?,?,?,?)",
                    messageId, user, emoji, Timestamp.from(now));
        db.update("UPDATE chat_messages SET revision=? WHERE id=?", revision, messageId);
    }

    /** Reaction totals per message, in the order each emoji was first used, marking the user's own. */
    public Map<UUID, List<Reaction>> findReactions(UUID user, Collection<UUID> messages) {
        var found = new HashMap<UUID, List<Reaction>>();
        if (messages.isEmpty()) return found;
        String marks = String.join(",", Collections.nCopies(messages.size(), "?"));
        var params = new ArrayList<Object>(List.of(user));
        params.addAll(messages);
        db.query("SELECT message_id,emoji,count(*) AS total,MAX(CASE WHEN user_id=? THEN 1 ELSE 0 END) AS mine "
                        + "FROM chat_reactions WHERE message_id IN (" + marks + ") GROUP BY message_id,emoji "
                        + "ORDER BY MIN(created_at)",
                rs -> {
                    found.computeIfAbsent(rs.getObject("message_id", UUID.class), id -> new ArrayList<>())
                            .add(new Reaction(rs.getString("emoji"), rs.getInt("total"), rs.getInt("mine") == 1));
                }, params.toArray());
        return found;
    }

    public void lockConversation(UUID id) {
        db.queryForObject("SELECT id FROM chat_conversations WHERE id=? FOR UPDATE", UUID.class, id);
    }

    public List<Message> findRetry(UUID id, UUID user, UUID clientId) {
        return db.query(MESSAGES + "WHERE m.conversation_id=? AND m.sender_id=? AND m.client_id=?", this::message, id, user, clientId);
    }

    public int insertMessage(UUID messageId, UUID id, UUID user, UUID clientId, String body, Instant sentAt, UUID fileId,
            UUID replyTo, boolean forwarded) {
        String conflict = postgres ? " ON CONFLICT (conversation_id,sender_id,client_id) DO NOTHING" : "";
        // H2 lacks ON CONFLICT; the caller's conversation lock serializes duplicate checks.
        return db.update("INSERT INTO chat_messages(id,conversation_id,sender_id,client_id,body,sent_at,file_id,"
                        + "reply_to_id,forwarded) VALUES (?,?,?,?,?,?,?,?,?)" + conflict,
                messageId, id, user, clientId, body, Timestamp.from(sentAt), fileId, replyTo, forwarded);
    }

    public void updateRecency(UUID id, Instant updatedAt) {
        Timestamp now = Timestamp.from(updatedAt);
        db.update("UPDATE chat_conversations SET updated_at=CASE WHEN updated_at<? THEN ? ELSE updated_at END WHERE id=?", now, now, id);
    }

    public Message findMessage(UUID id) {
        return db.queryForObject(MESSAGES + "WHERE m.id=?", this::message, id);
    }

    public List<String> findTypingRecipients(UUID conversation, UUID user) {
        return db.queryForList("SELECT u.username FROM chat_members m JOIN app_users u ON u.id=m.user_id "
                + "WHERE m.conversation_id=? AND m.user_id<>? AND m.left_at IS NULL AND u.enabled=true",
                String.class, conversation, user);
    }

    public List<String> findMemberUsernames(UUID conversation) {
        return db.queryForList("SELECT u.username FROM chat_members m JOIN app_users u ON u.id=m.user_id "
                + "WHERE m.conversation_id=? AND m.left_at IS NULL", String.class, conversation);
    }
}
