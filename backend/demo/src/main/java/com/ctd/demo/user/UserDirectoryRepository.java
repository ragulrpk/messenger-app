package com.ctd.demo.user;

import com.ctd.demo.chat.ChatService.Person;
import com.ctd.demo.chat.ChatService.Presence;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** User directory searches and account row locks used when opening direct conversations. */
@Repository
public class UserDirectoryRepository {
    private final JdbcTemplate db;

    public UserDirectoryRepository(JdbcTemplate db) { this.db = db; }

    public List<Person> search(UUID user, String term) {
        String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return db.query("SELECT id,username,display_name FROM app_users WHERE enabled=true AND id<>? "
                + "AND (lower(display_name) LIKE ? ESCAPE '!' OR username LIKE ? ESCAPE '!') ORDER BY username LIMIT 50",
                (rs, n) -> new Person(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name")),
                user, pattern, pattern);
    }

    /** Enabled users with the given (normalized) usernames, other than the caller. */
    public List<Person> findByUsernames(UUID user, java.util.Collection<String> usernames) {
        if (usernames.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(usernames.size(), "?"));
        var params = new java.util.ArrayList<Object>(usernames);
        params.add(user);
        return db.query("SELECT id,username,display_name FROM app_users WHERE enabled=true AND username IN (" + marks
                        + ") AND id<>? ORDER BY username",
                (rs, n) -> new Person(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name")),
                params.toArray());
    }

    /** Any users (including disabled ones) with the given usernames, to show who old mentions refer to. */
    public List<Person> findAnyByUsernames(java.util.Collection<String> usernames) {
        if (usernames.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(usernames.size(), "?"));
        return db.query("SELECT id,username,display_name FROM app_users WHERE username IN (" + marks + ")",
                (rs, n) -> new Person(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name")),
                usernames.toArray());
    }

    public void lockById(UUID user) {
        db.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE", UUID.class, user);
    }

    public Presence findPresence(UUID user) {
        var found = db.query("SELECT status,note FROM user_presence WHERE user_id=?",
                (rs, n) -> new Presence(rs.getString("status"), rs.getString("note")), user);
        return found.isEmpty() ? new Presence("online", "") : found.getFirst();
    }

    /** Saves a user's presence; the caller's account-row lock serializes the update-or-insert. */
    public void updatePresence(UUID user, Presence presence) {
        Timestamp now = Timestamp.from(Instant.now());
        lockById(user);
        if (db.update("UPDATE user_presence SET status=?,note=?,updated_at=? WHERE user_id=?",
                presence.status(), presence.note(), now, user) == 0)
            db.update("INSERT INTO user_presence(user_id,status,note,updated_at) VALUES (?,?,?,?)",
                    user, presence.status(), presence.note(), now);
    }

    /** The user and everyone who shares a conversation with them: the people who can see their status. */
    public List<String> findContactUsernames(UUID user) {
        return db.queryForList("SELECT DISTINCT u.username FROM chat_members mine "
                + "JOIN chat_members other ON other.conversation_id=mine.conversation_id AND other.left_at IS NULL "
                + "JOIN app_users u ON u.id=other.user_id WHERE mine.user_id=? AND mine.left_at IS NULL AND u.enabled=true",
                String.class, user);
    }
}
