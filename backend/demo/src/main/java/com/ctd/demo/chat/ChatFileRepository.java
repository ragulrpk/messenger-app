package com.ctd.demo.chat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC persistence for uploaded file metadata and retention claims. */
@Repository
public class ChatFileRepository {
    private final JdbcTemplate db;

    public ChatFileRepository(JdbcTemplate db) { this.db = db; }

    public record StoredFile(String storageKey, String name, String contentType, long size,
                             Instant expiresAt, Instant deletedAt) { }
    public record ExpiredFile(UUID id, String storageKey) { }

    /** Stores a file at its place among the files sent together; it is linked to its message once that exists. */
    public void insert(UUID fileId, UUID conversation, UUID user, ChatService.NewFile file, Instant createdAt, int order) {
        db.update("INSERT INTO chat_files(id,conversation_id,uploader_id,original_name,content_type,size_bytes,sha256,"
                        + "storage_key,created_at,expires_at,file_order) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                fileId, conversation, user, file.name(), file.contentType(), file.size(), file.sha256(), file.storageKey(),
                Timestamp.from(createdAt), Timestamp.from(file.expiresAt()), order);
    }

    public void linkToMessage(UUID messageId, List<UUID> fileIds) {
        for (UUID id : fileIds) db.update("UPDATE chat_files SET message_id=? WHERE id=?", messageId, id);
    }

    public void delete(UUID id) { db.update("DELETE FROM chat_files WHERE id=?", id); }

    /** The content hashes of a message's files, in the order they were sent. */
    public List<String> findSha256s(UUID messageId) {
        return db.queryForList("SELECT sha256 FROM chat_files WHERE message_id=? ORDER BY file_order", String.class, messageId);
    }

    public List<UUID> findIdsForMessage(UUID messageId) {
        return db.queryForList("SELECT id FROM chat_files WHERE message_id=? ORDER BY file_order", UUID.class, messageId);
    }

    /** A message's stored files, in the order they were sent, for forwarding. */
    public List<StoredFile> findForMessage(UUID messageId) {
        return db.query("SELECT storage_key,original_name,content_type,size_bytes,expires_at,deleted_at FROM chat_files "
                + "WHERE message_id=? ORDER BY file_order", ChatFileRepository::storedFile, messageId);
    }

    /** The files each message carries, in the order they were sent. */
    public Map<UUID, List<ChatService.Attachment>> findAttachments(Collection<UUID> messages) {
        var found = new HashMap<UUID, List<ChatService.Attachment>>();
        if (messages.isEmpty()) return found;
        String marks = String.join(",", Collections.nCopies(messages.size(), "?"));
        Instant now = Instant.now();
        db.query("SELECT id,message_id,original_name,content_type,size_bytes,expires_at,deleted_at FROM chat_files "
                        + "WHERE message_id IN (" + marks + ") ORDER BY message_id,file_order",
                rs -> {
                    boolean available = rs.getTimestamp("deleted_at") == null
                            && rs.getTimestamp("expires_at").toInstant().isAfter(now);
                    found.computeIfAbsent(rs.getObject("message_id", UUID.class), id -> new ArrayList<>())
                            .add(new ChatService.Attachment(rs.getObject("id", UUID.class), rs.getString("original_name"),
                                    rs.getString("content_type"), rs.getLong("size_bytes"), available));
                }, messages.toArray());
        return found;
    }

    private static StoredFile storedFile(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new StoredFile(rs.getString("storage_key"), rs.getString("original_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());
    }

    public List<StoredFile> findForMember(UUID user, UUID fileId) {
        return db.query("SELECT f.storage_key,f.original_name,f.content_type,f.size_bytes,f.expires_at,f.deleted_at "
                        + "FROM chat_files f JOIN chat_members m ON m.conversation_id=f.conversation_id AND m.user_id=? "
                        + "AND m.left_at IS NULL JOIN chat_messages fm ON fm.id=f.message_id "
                        + "AND fm.sequence>m.history_from_sequence WHERE f.id=?",
                ChatFileRepository::storedFile, user, fileId);
    }

    /** Must be called inside the transaction that deletes bytes and marks the claimed rows. */
    public List<ExpiredFile> claimExpired(Instant now, int limit) {
        return db.query("SELECT id,storage_key FROM chat_files WHERE deleted_at IS NULL AND expires_at<=? "
                        + "AND (purge_retry_at IS NULL OR purge_retry_at<=?) ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new ExpiredFile(rs.getObject("id", UUID.class), rs.getString("storage_key")),
                Timestamp.from(now), Timestamp.from(now), limit);
    }

    /** Skips a file whose bytes could not be removed until a later retention run. */
    public void deferPurge(UUID id, Instant until) {
        db.update("UPDATE chat_files SET purge_retry_at=? WHERE id=?", Timestamp.from(until), id);
    }

    /** Bytes the user stored since a time, counting files later deleted or expired: the quota limits the rate. */
    public long storedSince(UUID user, Instant since) {
        return db.queryForObject("SELECT COALESCE(SUM(size_bytes),0) FROM chat_files WHERE uploader_id=? AND created_at>?",
                Long.class, user, Timestamp.from(since));
    }

    /** Makes a file unavailable now; the retention job then removes its bytes on its next pass. */
    public void expire(UUID id, Instant now) {
        db.update("UPDATE chat_files SET expires_at=? WHERE id=? AND expires_at>?", Timestamp.from(now), id, Timestamp.from(now));
    }

    /** Claims one expired file, unless another replica's retention run holds it. */
    public List<ExpiredFile> claimExpiredById(UUID id, Instant now) {
        return db.query("SELECT id,storage_key FROM chat_files WHERE id=? AND deleted_at IS NULL AND expires_at<=? "
                        + "FOR UPDATE SKIP LOCKED",
                (rs, n) -> new ExpiredFile(rs.getObject("id", UUID.class), rs.getString("storage_key")), id, Timestamp.from(now));
    }

    public void markDeleted(UUID id, Instant now) {
        db.update("UPDATE chat_files SET deleted_at=? WHERE id=?", Timestamp.from(now), id);
    }
}
