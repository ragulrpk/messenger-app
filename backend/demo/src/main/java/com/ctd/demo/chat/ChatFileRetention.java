package com.ctd.demo.chat;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Deletes expired file bytes while keeping their metadata so messages can show the file as expired. */
@Component
public class ChatFileRetention {
    private static final Logger log = LoggerFactory.getLogger(ChatFileRetention.class);
    private static final int BATCH = 100;
    private final JdbcTemplate db;
    private final FileStorage storage;
    private final TransactionTemplate transactions;

    public ChatFileRetention(JdbcTemplate db, FileStorage storage, TransactionTemplate transactions) {
        this.db = db;
        this.storage = storage;
        this.transactions = transactions;
    }

    @Scheduled(initialDelayString = "${app.files.cleanup-initial-delay:1m}", fixedDelayString = "${app.files.cleanup-interval:1h}")
    void scheduledPurge() {
        try {
            int purged = purgeExpired(Instant.now());
            int stale = storage.deleteStaleUploads(Instant.now().minus(Duration.ofDays(1)));
            if (purged > 0 || stale > 0) log.info("File retention removed {} expired files and {} stale uploads", purged, stale);
        } catch (IOException | RuntimeException exception) {
            log.error("File retention run failed", exception);
        }
    }

    /** Purges files expired at the given time; replicas claim disjoint batches, so concurrent runs are safe. */
    public int purgeExpired(Instant now) {
        int total = 0;
        for (Integer purged; (purged = transactions.execute(status -> purgeBatch(now))) != null && purged > 0; ) {
            total += purged;
            if (purged < BATCH) break;
        }
        return total;
    }

    private int purgeBatch(Instant now) {
        Timestamp cutoff = Timestamp.from(now);
        List<String[]> claimed = db.query("SELECT id,storage_key FROM chat_files WHERE deleted_at IS NULL AND expires_at<=? "
                        + "ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new String[] { rs.getString("id"), rs.getString("storage_key") }, cutoff, BATCH);
        for (String[] file : claimed) {
            // Bytes go first: a rollback after this leaves the row claimable and the delete is idempotent.
            storage.delete(file[1]);
            db.update("UPDATE chat_files SET deleted_at=? WHERE id=?", cutoff, UUID.fromString(file[0]));
        }
        return claimed.size();
    }
}
