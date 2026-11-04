package com.ctd.demo.chat;

import com.ctd.demo.auth.CredentialStamp;
import com.ctd.demo.user.UserRepository;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Per-browser subscriptions and a database-backed push cursor with bounded retries. */
@Component
@RestController
@RequestMapping("/api/v1/chat/push")
public class ChatPush implements LogoutHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatPush.class);
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat-push-", 0).factory());
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    @jakarta.annotation.PreDestroy
    public void close() { worker.shutdownNow(); }
    private final JdbcTemplate db;
    private final UserRepository users;
    private final ChatNotifications notifications;
    private final WebPushTransport transport;
    private final TransactionTemplate transactions;
    private final org.springframework.security.core.session.SessionRegistry registry;
    public ChatPush(JdbcTemplate db, UserRepository users, ChatNotifications notifications, WebPushTransport transport, TransactionTemplate transactions,
            org.springframework.security.core.session.SessionRegistry registry) {
        this.db = db; this.users = users; this.notifications = notifications; this.transport = transport; this.transactions = transactions;
        this.registry = registry;
    }
    public record Keys(@NotBlank @Size(max=128) String p256dh, @NotBlank @Size(max=64) String auth) { }
    public record Subscription(@NotBlank @Size(max=2048) String endpoint, @NotNull @Valid Keys keys) { }
    @GetMapping("/config")
    public Object config() { return Map.of("enabled", transport.enabled(), "publicKey", transport.publicKey()); }
    @PostMapping("/subscribe")
    public Object subscribe(Authentication authentication, HttpServletRequest request, @Valid @RequestBody Subscription subscription) {
        if (!transport.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Background notifications are not configured.");
        try { WebPushTransport.validate(subscription.endpoint(), subscription.keys().p256dh(), subscription.keys().auth()); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid browser push subscription."); }
        var user = users.findByUsername(authentication.getName()).filter(u -> u.isEnabled()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var session = request.getSession(false);
        if (session == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        transactions.executeWithoutResult(status -> {
            db.queryForObject("SELECT id FROM chat_notification_clock WHERE id=1 FOR UPDATE", Integer.class);
            String id = endpointId(subscription.endpoint());
            if (db.queryForObject("SELECT count(*) FROM chat_push_subscriptions WHERE user_id=? AND id<>?", Integer.class, user.getId(), id) >= 10)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many notification devices.");
            var existing = db.query("SELECT user_id,credential_stamp,cursor_position FROM chat_push_subscriptions WHERE id=?",
                    (rs,n) -> Map.entry(rs.getObject(1, UUID.class).equals(user.getId()) && CredentialStamp.matches(rs.getString(2),user.getPasswordHash()),rs.getLong(3)),id);
            long cursor = !existing.isEmpty() && existing.getFirst().getKey() ? existing.getFirst().getValue() : notifications.position();
            db.update("DELETE FROM chat_push_subscriptions WHERE id=?", id);
            Instant now = Instant.now();
            db.update("INSERT INTO chat_push_subscriptions(id,user_id,session_id,credential_stamp,endpoint,public_key,auth_secret,cursor_position,retry_at,updated_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?)", id, user.getId(), session.getId(), CredentialStamp.of(user.getPasswordHash()), subscription.endpoint(),
                    subscription.keys().p256dh(), subscription.keys().auth(), cursor, Timestamp.from(now), Timestamp.from(now));
        });
        return Map.of("subscribed", true);
    }
    @PostMapping("/unsubscribe")
    public Object unsubscribe(Authentication authentication, @RequestBody Map<String,String> body) {
        var user = users.findByUsername(authentication.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        db.update("DELETE FROM chat_push_subscriptions WHERE id=? AND user_id=?", endpointId(body.getOrDefault("endpoint", "")), user.getId());
        return Map.of("subscribed", false);
    }
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        var session = request.getSession(false);
        if (session != null) db.update("DELETE FROM chat_push_subscriptions WHERE session_id=?", session.getId());
    }
    static String endpointId(String endpoint) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private record Pending(String id, UUID user, String stamp, String endpoint, String key, String auth, long cursor, int failures,
                           String session) { }
    @Scheduled(initialDelay=30000, fixedDelay=1000)
    public void scheduleDelivery() {
        if (transport.enabled() && running.compareAndSet(false,true)) worker.execute(() -> {
            try { deliver(); } finally { running.set(false); }
        });
    }
    /** A subscription reserved for one send until its lease time, with the notification to send. */
    private record Claim(Pending subscription, ChatNotifications.Item item, Timestamp lease) { }
    /** Longer than one send can take (3 s connect + 5 s request), so a lease only lapses if its worker died. */
    private static final long LEASE_SECONDS = 60;

    /** Sends one notification per pass until nothing is due; subscriptions with nothing new are never touched. */
    public void deliver() {
        if (!transport.enabled()) return;
        for (int count = 0; count < 200; count++) {
            try {
                // null: nothing is due. Empty: a subscription was settled without a send.
                Optional<Claim> claim = transactions.execute(status -> claim(Instant.now()));
                if (claim == null) break;
                claim.ifPresent(this::send);
            } catch (RuntimeException exception) { log.warn("Background notification delivery unavailable; retrying later"); break; }
        }
    }
    /** A short transaction: locks a due subscription that has unsent notifications and leases it. The lease
     *  (retry_at in the future) keeps other replicas away after the commit, so the send needs no lock. */
    private Optional<Claim> claim(Instant now) {
        long watermark = notifications.position();
        var due = db.query("SELECT * FROM chat_push_subscriptions s WHERE s.retry_at<=? AND EXISTS (SELECT 1 FROM chat_notifications n "
                        + "WHERE n.user_id=s.user_id AND n.position>s.cursor_position AND n.position<=?) "
                        + "ORDER BY s.retry_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                (rs,n) -> new Pending(rs.getString("id"),rs.getObject("user_id",UUID.class),rs.getString("credential_stamp"),rs.getString("endpoint"),
                    rs.getString("public_key"),rs.getString("auth_secret"),rs.getLong("cursor_position"),rs.getInt("failures"),
                    rs.getString("session_id")),
                Timestamp.from(now), watermark);
        if (due.isEmpty()) return null;
        var pending = due.getFirst();
        var user = users.findById(pending.user());
        // A browser is notified only while the session that subscribed it lasts: signing out, signing in elsewhere
        // or five idle minutes end it, and a notification must not show messages on a signed-out device.
        var session = registry.getSessionInformation(pending.session());
        if (user.isEmpty() || !user.get().isEnabled() || !CredentialStamp.matches(pending.stamp(), user.get().getPasswordHash())
                || session == null || session.isExpired()) {
            db.update("DELETE FROM chat_push_subscriptions WHERE id=?", pending.id()); return Optional.empty();
        }
        var page = notifications.page(pending.user(), pending.cursor(), watermark);
        if (page.notifications().isEmpty()) {
            // Everything new was read, muted or removed meanwhile: move past it without sending.
            db.update("UPDATE chat_push_subscriptions SET cursor_position=? WHERE id=?", page.cursor(), pending.id());
            return Optional.empty();
        }
        // Microseconds, the column's precision, so the lease compares equal when the outcome is recorded.
        var lease = Timestamp.from(now.plusSeconds(LEASE_SECONDS).truncatedTo(ChronoUnit.MICROS));
        db.update("UPDATE chat_push_subscriptions SET retry_at=? WHERE id=?", lease, pending.id());
        return Optional.of(new Claim(pending, page.notifications().getFirst(), lease));
    }
    /** Calls the push service with no transaction open, then records the outcome only if the lease is still
     *  this worker's: a re-subscription or another replica taking over a lapsed lease wins. */
    private void send(Claim claim) {
        var pending = claim.subscription();
        var item = claim.item();
        int result;
        try {
            byte[] payload = JSON.writeValueAsBytes(Map.of("title",item.title(),"body",item.body(),"tag","message-" + item.messageId(),
                "conversationId",item.conversationId(),"userId",item.userId(),"position",item.position(),"mentioned",item.mentioned()));
            result = transport.send(pending.endpoint(), pending.key(), pending.auth(), payload);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            result = -1;
        }
        String leased = " WHERE id=? AND retry_at=?";
        if (result == 404 || result == 410) {
            db.update("DELETE FROM chat_push_subscriptions" + leased, pending.id(), claim.lease());
        } else if (result >= 200 && result < 300) {
            // Due again at once; the next pass sends the following notification, if there is one.
            db.update("UPDATE chat_push_subscriptions SET cursor_position=?,failures=0,retry_at=?" + leased,
                    item.position(), Timestamp.from(Instant.now()), pending.id(), claim.lease());
        } else {
            int failures = pending.failures() + 1;
            db.update("UPDATE chat_push_subscriptions SET failures=?,retry_at=?" + leased, failures,
                    Timestamp.from(Instant.now().plusSeconds(Math.min(3600, 5L << Math.min(failures, 9)))), pending.id(), claim.lease());
            log.debug("Background notification delayed; retry scheduled");
        }
    }
    @Scheduled(initialDelay=3600000, fixedDelay=3600000)
    public void cleanup() {
        db.update("DELETE FROM chat_notifications WHERE created_at<?", Timestamp.from(Instant.now().minusSeconds(7*86400)));
        db.update("DELETE FROM chat_push_subscriptions WHERE updated_at<?", Timestamp.from(Instant.now().minusSeconds(90*86400)));
    }
}
