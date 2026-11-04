package com.ctd.demo.chat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** Deletes expired file bytes while keeping their metadata so messages can show the file as expired. */
@Component
public class ChatFileRetention {
    private static final Logger log = LoggerFactory.getLogger(ChatFileRetention.class);
    private static final int BATCH = 100;
    private static final Duration RETRY = Duration.ofHours(1);
    private final ChatFileRepository files;
    private final FileStorage storage;
    private final TransactionTemplate transactions;
    /** After a commit, data access must run in a new transaction or its changes would not be saved. */
    private final TransactionTemplate afterCommit;

    public ChatFileRetention(ChatFileRepository files, FileStorage storage, TransactionTemplate transactions) {
        this.files = files;
        this.storage = storage;
        this.transactions = transactions;
        this.afterCommit = new TransactionTemplate(transactions.getTransactionManager());
        this.afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Removes an unsent file's bytes as soon as the delete commits, instead of at the next hourly run. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void unsent(FileUnsent event) {
        try {
            afterCommit.executeWithoutResult(status -> {
                Instant now = Instant.now();
                for (var file : files.claimExpiredById(event.fileId(), now)) {
                    storage.delete(file.storageKey());
                    files.markDeleted(file.id(), now);
                }
            });
        } catch (RuntimeException exception) {
            // The file is already expired, so the scheduled run will remove it.
            log.warn("Immediate removal of an unsent file failed; the retention job will retry");
        }
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

    private record Batch(int claimed, int purged) { }

    /** Purges files expired at the given time and returns how many were removed; replicas claim disjoint
     *  batches, so concurrent runs are safe. */
    public int purgeExpired(Instant now) {
        int total = 0;
        for (Batch batch; (batch = transactions.execute(status -> purgeBatch(now))) != null && batch.claimed() > 0; ) {
            total += batch.purged();
            if (batch.claimed() < BATCH) break;
        }
        return total;
    }

    private Batch purgeBatch(Instant now) {
        var claimed = files.claimExpired(now, BATCH);
        int purged = 0;
        for (var file : claimed) {
            // Bytes go first: a rollback after this leaves the row claimable and the delete is idempotent.
            try {
                storage.delete(file.storageKey());
            } catch (RuntimeException exception) {
                // One file the volume refuses to delete must not stop every other expired file behind it.
                log.warn("Expired file {} could not be removed; retrying after {}", file.id(), RETRY, exception);
                files.deferPurge(file.id(), Instant.now().plus(RETRY));
                continue;
            }
            files.markDeleted(file.id(), now);
            purged++;
        }
        return new Batch(claimed.size(), purged);
    }
}
