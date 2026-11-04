package com.ctd.demo.chat;

import com.ctd.demo.user.UserRepository;
import com.ctd.demo.auth.CredentialStamp;
import jakarta.servlet.http.HttpSession;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 2026-09-23: Read-only live hints. HTTP remains authoritative for messages and authorization. */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    public static final String HTTP_SESSION = "chat.httpSession";
    /** When the socket's login was last checked against the session store and the account. */
    private static final String VALIDATED_AT = "chat.validatedAt";
    /** Hints within this long of a full check skip it. The heartbeat always checks fully, so revocation
     *  still closes sockets within one heartbeat, and a hint ("refresh", "typing") carries no data. */
    private static final long HINT_RECHECK_MILLIS = 10_000;
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    /** The same sockets by username, so a hint for one person never scans everyone's connections. */
    private final Map<String, Set<WebSocketSession>> byUser = new ConcurrentHashMap<>();
    /** The heartbeat checks every socket, so it runs off the scheduler thread shared with push and retention. */
    private final ExecutorService heartbeats = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat-heartbeat-", 0).factory());
    private final AtomicBoolean beating = new AtomicBoolean();
    @org.springframework.beans.factory.annotation.Value("${app.security.idle-timeout:5m}")
    private java.time.Duration idle = java.time.Duration.ofMinutes(5);
    private final SessionRegistry registry;
    private final UserRepository users;
    private final ObjectProvider<SessionRepository<?>> repositories;
    private final Environment environment;

    public ChatWebSocketHandler(SessionRegistry registry, UserRepository users,
            ObjectProvider<SessionRepository<?>> repositories, Environment environment) {
        this.registry = registry;
        this.users = users;
        this.repositories = repositories;
        this.environment = environment;
    }

    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) throws IOException {
        // Bound tabs per HTTP session; clients cannot select another user's subscription.
        HttpSession http = (HttpSession) session.getAttributes().get(HTTP_SESSION);
        if (http == null || !valid(session)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String username = session.getPrincipal().getName();
        long count = byUser.getOrDefault(username, Set.of()).stream().filter(s -> {
            HttpSession other = (HttpSession) s.getAttributes().get(HTTP_SESSION);
            return other.getId().equals(http.getId());
        }).count();
        if (count >= 8) {
            session.close(new CloseStatus(1008, "Too many connections"));
            return;
        }
        session.setTextMessageSizeLimit(128);
        session.setBinaryMessageSizeLimit(128);
        var safe = new ConcurrentWebSocketSessionDecorator(session, 5000, 4096);
        sockets.put(session.getId(), safe);
        byUser.computeIfAbsent(username, ignored -> ConcurrentHashMap.newKeySet()).add(safe);
        deliver(safe, "ready", false);
    }

    /** Recheck expiry, revocation and enabled state before every event; socket traffic never extends login. */
    private boolean valid(WebSocketSession socket) {
        try {
            if (socket.getPrincipal() == null) return false;
            String username = socket.getPrincipal().getName();
            HttpSession http = (HttpSession) socket.getAttributes().get(HTTP_SESSION);
            if (http == null) return false;
            var information = registry.getSessionInformation(http.getId());
            if (information == null || information.isExpired()) return false;
            SecurityContext context;
            Object stamp;
            Object lastActivity;
            if (environment.matchesProfiles("local", "test")) {
                int timeout = http.getMaxInactiveInterval();
                if (timeout > 0 && System.currentTimeMillis() - http.getLastAccessedTime() >= timeout * 1000L)
                    return false;
                context = (SecurityContext) http.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                stamp = http.getAttribute(CredentialStamp.ATTRIBUTE);
                lastActivity = http.getAttribute(com.ctd.demo.auth.LoginOrchestrationService.LAST_ACTIVITY);
            } else {
                var repository = repositories.getObject();
                var stored = repository.findById(http.getId());
                if (stored == null || stored.isExpired()) return false;
                context = stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                stamp = stored.getAttribute(CredentialStamp.ATTRIBUTE);
                lastActivity = stored.getAttribute(com.ctd.demo.auth.LoginOrchestrationService.LAST_ACTIVITY);
            }
            // Five minutes without real use ends the session even while the socket and polling keep it open.
            if (lastActivity instanceof Long last && System.currentTimeMillis() - last >= idle.toMillis()) return false;
            boolean valid = context != null && context.getAuthentication() != null
                    && context.getAuthentication().isAuthenticated()
                    && username.equals(context.getAuthentication().getName())
                    && users.findByUsername(username).filter(u -> u.isEnabled()
                            && CredentialStamp.matches(stamp, u.getPasswordHash())).isPresent();
            if (valid) socket.getAttributes().put(VALIDATED_AT, System.currentTimeMillis());
            return valid;
        } catch (RuntimeException exception) {
            // Redis/database outage or an invalidated servlet session fails closed.
            return false;
        }
    }

    public void changed(String username) {
        byUser.getOrDefault(username, Set.of()).forEach(s -> deliver(s, "refresh", false));
    }

    public void typing(ChatTyping event) {
        String hint = "typing:" + event.conversationId() + ":" + event.active();
        for (String username : event.usernames())
            byUser.getOrDefault(username, Set.of()).forEach(s -> deliver(s, hint, false));
    }

    /** Hands the 20-second heartbeat to its own thread; a pass still running skips the next one. */
    @Scheduled(fixedDelay = 20000)
    public void scheduleHeartbeat() {
        if (beating.compareAndSet(false, true)) heartbeats.execute(() -> {
            try { heartbeat(); } finally { beating.set(false); }
        });
    }

    /** Keeps proxies alive and fully rechecks every socket's login, closing revoked or expired ones. */
    public void heartbeat() { sockets.values().forEach(s -> deliver(s, "heartbeat", true)); }

    @PreDestroy
    public void shutdown() {
        heartbeats.shutdownNow();
        sockets.values().forEach(s -> close(s, CloseStatus.GOING_AWAY));
    }

    private void deliver(WebSocketSession socket, String event, boolean fullCheck) {
        Object validatedAt = socket.getAttributes().get(VALIDATED_AT);
        boolean recent = !fullCheck && validatedAt instanceof Long at && System.currentTimeMillis() - at < HINT_RECHECK_MILLIS;
        if (!recent && !valid(socket)) {
            close(socket, CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            socket.sendMessage(new TextMessage(event));
        } catch (IOException | RuntimeException exception) {
            close(socket, CloseStatus.SERVER_ERROR);
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        forget(session);
        try { session.close(status); } catch (IOException ignored) { /* Already disconnected. */ }
    }

    /** Drops a socket from both indexes; the stored decorator is found by ID, since Spring passes the raw session. */
    private void forget(WebSocketSession session) {
        var stored = sockets.remove(session.getId());
        if (stored == null || stored.getPrincipal() == null) return;
        byUser.computeIfPresent(stored.getPrincipal().getName(), (ignored, set) -> {
            set.remove(stored);
            return set.isEmpty() ? null : set;
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // No client commands/subscriptions: messages are sent through the CSRF-protected REST endpoint.
        close(session, CloseStatus.POLICY_VIOLATION);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        close(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        forget(session);
    }
}
