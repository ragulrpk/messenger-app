package com.ctd.demo.auth;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Six-digit codes emailed to an account owner. Stored hashed; each code is single-use, valid for five minutes
 *  and allows five attempts, and an account can be sent at most three codes per purpose in fifteen minutes. */
@Service
public class OneTimeCodes {
    public enum Purpose { ADMIN_LOGIN, FIRST_LOGIN, PASSWORD_RESET }
    static final Duration VALIDITY = Duration.ofMinutes(5);
    static final int ATTEMPTS = 5;
    static final int SENDS = 3;
    static final Duration SEND_WINDOW = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;

    public OneTimeCodes(JdbcTemplate db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    /** Creates a code, replacing any earlier unused one for the same purpose, and returns it for emailing.
     *  Empty when the account has been sent too many codes recently. */
    public java.util.Optional<String> issue(UUID user, Purpose purpose) {
        Instant now = Instant.now();
        int recent = db.queryForObject("SELECT count(*) FROM one_time_codes WHERE user_id=? AND purpose=? AND created_at>?",
                Integer.class, user, purpose.name(), Timestamp.from(now.minus(SEND_WINDOW)));
        if (recent >= SENDS) return java.util.Optional.empty();
        // Only the newest code works.
        db.update("UPDATE one_time_codes SET consumed_at=? WHERE user_id=? AND purpose=? AND consumed_at IS NULL",
                Timestamp.from(now), user, purpose.name());
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        db.update("INSERT INTO one_time_codes(id,user_id,purpose,code_hash,created_at,expires_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), user, purpose.name(), encoder.encode(code), Timestamp.from(now), Timestamp.from(now.plus(VALIDITY)));
        return java.util.Optional.of(code);
    }

    /** Checks a code against the newest live one. A match consumes it; a miss uses up an attempt. Runs in its
     *  own transaction so a failed attempt stays counted when the caller rejects the request; callers validate
     *  everything else first, so a valid code is not used up by an unrelated mistake. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean verify(UUID user, Purpose purpose, String code) {
        var live = db.query("SELECT id,code_hash,attempts FROM one_time_codes WHERE user_id=? AND purpose=? AND consumed_at IS NULL "
                        + "AND expires_at>? ORDER BY created_at DESC LIMIT 1 FOR UPDATE",
                (rs, n) -> new Object[] { rs.getObject("id", UUID.class), rs.getString("code_hash"), rs.getInt("attempts") },
                user, purpose.name(), Timestamp.from(Instant.now()));
        if (live.isEmpty()) return false;
        UUID id = (UUID) live.getFirst()[0];
        int attempts = (int) live.getFirst()[2];
        if (attempts >= ATTEMPTS) return false;
        if (code != null && code.matches("\\d{6}") && encoder.matches(code, (String) live.getFirst()[1])) {
            db.update("UPDATE one_time_codes SET consumed_at=? WHERE id=?", Timestamp.from(Instant.now()), id);
            return true;
        }
        db.update("UPDATE one_time_codes SET attempts=attempts+1 WHERE id=?", id);
        return false;
    }

    /** Drops codes older than a day; they can no longer be used or count toward the send limit. */
    public void prune() {
        db.update("DELETE FROM one_time_codes WHERE created_at<?", Timestamp.from(Instant.now().minus(Duration.ofDays(1))));
    }
}
