package com.ctd.demo.chat;

import com.ctd.demo.user.UserRepository;
import com.ctd.demo.auth.CredentialStamp;
import jakarta.servlet.http.HttpSession;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
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
        long count = sockets.values().stream().filter(s -> {
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
        deliver(safe, "ready");
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
            if (environment.matchesProfiles("local", "test")) {
                int timeout = http.getMaxInactiveInterval();
                if (timeout > 0 && System.currentTimeMillis() - http.getLastAccessedTime() >= timeout * 1000L)
                    return false;
                context = (SecurityContext) http.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                stamp = http.getAttribute(CredentialStamp.ATTRIBUTE);
            } else {
                var repository = repositories.getObject();
                var stored = repository.findById(http.getId());
                if (stored == null || stored.isExpired()) return false;
                context = stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                stamp = stored.getAttribute(CredentialStamp.ATTRIBUTE);
            }
            return context != null && context.getAuthentication() != null
                    && context.getAuthentication().isAuthenticated()
                    && username.equals(context.getAuthentication().getName())
                    && users.findByUsername(username).filter(u -> u.isEnabled()
                            && CredentialStamp.matches(stamp, u.getPasswordHash())).isPresent();
        } catch (RuntimeException exception) {
            // Redis/database outage or an invalidated servlet session fails closed.
            return false;
        }
    }

    public void changed(String username) {
        sockets.values().stream().filter(s -> s.getPrincipal() != null
                && username.equals(s.getPrincipal().getName())).forEach(s -> deliver(s, "refresh"));
    }

    /** Server heartbeat keeps proxies alive and checks idle sessions on each 20-second scheduled pass. */
    @Scheduled(fixedDelay = 20000)
    public void heartbeat() { sockets.values().forEach(s -> deliver(s, "heartbeat")); }

    @PreDestroy
    public void shutdown() { sockets.values().forEach(s -> close(s, CloseStatus.GOING_AWAY)); }

    private void deliver(WebSocketSession socket, String event) {
        if (!valid(socket)) {
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
        sockets.remove(session.getId());
        try { session.close(status); } catch (IOException ignored) { /* Already disconnected. */ }
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
        sockets.remove(session.getId());
    }
}
