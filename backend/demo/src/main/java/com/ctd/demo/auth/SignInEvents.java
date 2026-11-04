package com.ctd.demo.auth;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Sign-in history shown to administrators. Never records passwords or codes. */
@Component
public class SignInEvents {
    public enum Outcome { SIGNED_IN, WRONG_PASSWORD, UNKNOWN_ACCOUNT, DISABLED, CODE_SENT, WRONG_CODE, PASSWORD_RESET,
        WRONG_LOGIN_PAGE }
    public record Event(Instant occurredAt, String loginId, String outcome, String clientAddress) { }
    private final JdbcTemplate db;

    public SignInEvents(JdbcTemplate db) { this.db = db; }

    /** Joins the caller's transaction. A failed step is recorded by the caller after its transaction has rolled
     *  back: a separate transaction here would wait for the account row the caller has locked. */
    @Transactional
    public void record(UUID user, String loginId, Outcome outcome, String clientAddress) {
        db.update("INSERT INTO sign_in_events(id,user_id,login_id,occurred_at,outcome,client_address) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), user, truncate(loginId, 64), Timestamp.from(Instant.now()), outcome.name(),
                truncate(clientAddress, 64));
    }

    public List<Event> forUser(UUID user, int limit) {
        return db.query("SELECT occurred_at,login_id,outcome,client_address FROM sign_in_events WHERE user_id=? "
                + "ORDER BY occurred_at DESC LIMIT ?", (rs, n) -> new Event(rs.getTimestamp(1).toInstant(), rs.getString(2),
                rs.getString(3), rs.getString(4)), user, limit);
    }

    public List<Event> recent(int limit) {
        return db.query("SELECT occurred_at,login_id,outcome,client_address FROM sign_in_events ORDER BY occurred_at DESC LIMIT ?",
                (rs, n) -> new Event(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getString(4)), limit);
    }

    public void prune() {
        db.update("DELETE FROM sign_in_events WHERE occurred_at<?", Timestamp.from(Instant.now().minus(Duration.ofDays(180))));
    }

    private static String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
