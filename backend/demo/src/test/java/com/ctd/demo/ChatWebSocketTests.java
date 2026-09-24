package com.ctd.demo;

import com.ctd.demo.auth.SessionRevocationService;
import com.ctd.demo.chat.ChatService;
import com.ctd.demo.chat.ChatWebSocketHandler;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 2026-09-23: Real HTTP upgrade tests, including access control and commit/rollback behavior. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "app.security.login-rate-limit.attempts=100"})
@ActiveProfiles("test")
class ChatWebSocketTests {
    @Value("${local.server.port}") int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate db;
    @Autowired ChatService chat;
    @Autowired ChatWebSocketHandler sockets;
    @Autowired SessionRevocationService revocation;
    @Autowired PlatformTransactionManager transactions;
    UUID bob;

    @AfterEach
    void cleanMessages() {
        db.update("DELETE FROM chat_messages");
        db.update("DELETE FROM chat_members");
        db.update("DELETE FROM chat_conversations");
    }

    @BeforeEach
    void seed() {
        db.update("DELETE FROM chat_messages");
        db.update("DELETE FROM chat_members");
        db.update("DELETE FROM chat_conversations");
        users.deleteAll();
        String hash = passwords.encode("test-password-123");
        users.save(new AppUser("ws-alice", "Alice", hash));
        bob = users.save(new AppUser("ws-bob", "Bob", hash)).getId();
        users.save(new AppUser("ws-eve", "Eve", hash));
    }

    static class Inbox implements WebSocket.Listener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder frame = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            frame.append(text);
            if (last) {
                if (!"heartbeat".contentEquals(frame)) events.add(frame.toString());
                frame.setLength(0);
            }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status);
            return null;
        }
        String next() throws Exception {
            String event;
            do { event = events.poll(3, TimeUnit.SECONDS); } while ("heartbeat".equals(event));
            return event;
        }
    }

    String url(String path) { return "http://127.0.0.1:" + port + path; }
    String token(HttpClient client) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(url("/api/v1/users/csrf"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return JsonPath.read(response.body(), "$.token");
    }
    HttpClient login(String username) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var response = client.send(HttpRequest.newBuilder(URI.create(url("/api/v1/users/login")))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token(client))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username
                        + "\",\"password\":\"test-password-123\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return client;
    }
    WebSocket connect(HttpClient client, Inbox inbox, String origin) throws Exception {
        var builder = client.newWebSocketBuilder();
        if (origin != null) builder.header("Origin", origin);
        return builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/chat/events"), inbox)
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void rejectsAnonymousForeignAndMissingOrigins() throws Exception {
        try (var anonymous = HttpClient.newHttpClient(); var alice = login("ws-alice")) {
            assertThatThrownBy(() -> connect(anonymous, new Inbox(), "http://localhost:5173"))
                    .hasCauseInstanceOf(WebSocketHandshakeException.class);
            assertThatThrownBy(() -> connect(alice, new Inbox(), "https://evil.example"))
                    .hasCauseInstanceOf(WebSocketHandshakeException.class);
            assertThatThrownBy(() -> connect(alice, new Inbox(), null))
                    .hasCauseInstanceOf(WebSocketHandshakeException.class);
        }
    }

    @Test
    void committedChangesReachOnlyParticipantsAndRetryDoesNotRepublish() throws Exception {
        try (var alice = login("ws-alice"); var other = login("ws-bob"); var eve = login("ws-eve")) {
            Inbox a = new Inbox(), b = new Inbox(), e = new Inbox();
            var wa = connect(alice, a, "http://localhost:5173");
            var wb = connect(other, b, "http://localhost:5173");
            var we = connect(eve, e, "http://localhost:5173");
            try {
                assertThat(a.next()).isEqualTo("ready");
                assertThat(b.next()).isEqualTo("ready");
                assertThat(e.next()).isEqualTo("ready");
                UUID conversation = chat.direct("ws-alice", bob).id();
                assertThat(a.next()).isEqualTo("refresh");
                assertThat(b.next()).isEqualTo("refresh");
                var tx = new TransactionTemplate(transactions);
                tx.executeWithoutResult(status -> {
                    chat.send("ws-alice", conversation, UUID.randomUUID(), "rolled back");
                    assertThat(a.events).isEmpty();
                    status.setRollbackOnly();
                });
                assertThat(a.events.poll(150, TimeUnit.MILLISECONDS)).isNull();
                assertThat(a.events).isEmpty();
                UUID retry = UUID.randomUUID();
                chat.send("ws-alice", conversation, retry, "private text");
                assertThat(a.next()).isEqualTo("refresh");
                assertThat(b.next()).isEqualTo("refresh");
                chat.send("ws-alice", conversation, retry, "private text");
                assertThat(a.events.poll(150, TimeUnit.MILLISECONDS)).isNull();
                assertThat(a.events).isEmpty();
                assertThat(b.events).isEmpty();
                assertThat(e.events).isEmpty();
                assertThat(chat.history("ws-bob", conversation, null, null).messages()).hasSize(1);
            } finally { wa.abort(); wb.abort(); we.abort(); }
        }
    }

    @Test
    void logoutRevocationAndDisabledAccountCloseExistingSockets() throws Exception {
        try (var alice = login("ws-alice"); var other = login("ws-bob"); var eve = login("ws-eve")) {
            Inbox a = new Inbox(), b = new Inbox(), e = new Inbox();
            var wa = connect(alice, a, "http://localhost:5173");
            var wb = connect(other, b, "http://localhost:5173");
            var we = connect(eve, e, "http://localhost:5173");
            try {
                assertThat(a.next()).isEqualTo("ready");
                assertThat(b.next()).isEqualTo("ready");
                assertThat(e.next()).isEqualTo("ready");
                var response = alice.send(HttpRequest.newBuilder(URI.create(url("/api/v1/users/logout")))
                        .header("X-CSRF-TOKEN", token(alice)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.discarding());
                assertThat(response.statusCode()).isEqualTo(204);
                revocation.revokeAll("ws-bob");
                db.update("UPDATE app_users SET enabled=false WHERE username='ws-eve'");
                sockets.heartbeat();
                assertThat(a.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
                assertThat(b.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
                assertThat(e.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
            } finally { wa.abort(); wb.abort(); we.abort(); }
        }
    }

    @Test
    void rejectsClientCommands() throws Exception {
        try (var alice = login("ws-alice")) {
            Inbox inbox = new Inbox();
            var socket = connect(alice, inbox, "http://localhost:5173");
            try {
                assertThat(inbox.next()).isEqualTo("ready");
                socket.sendText("subscribe:ws-eve", true).join();
                assertThat(inbox.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
            } finally { socket.abort(); }
        }
    }

    @Test
    void passwordChangeClosesExistingSocketInAnotherBrowser() throws Exception {
        try (var first = login("ws-alice"); var second = login("ws-alice")) {
            var inbox = new Inbox();
            var socket = connect(second, inbox, "http://localhost:5173");
            try {
                assertThat(inbox.next()).isEqualTo("ready");
                var result = first.send(HttpRequest.newBuilder(URI.create(url("/api/v1/users/password")))
                        .header("X-CSRF-TOKEN", token(first)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"currentPassword\":\"test-password-123\","
                                + "\"newPassword\":\"replacement-password-123\",\"confirmPassword\":\"replacement-password-123\"}"))
                        .build(), HttpResponse.BodyHandlers.discarding());
                assertThat(result.statusCode()).isEqualTo(204);
                sockets.heartbeat();
                assertThat(inbox.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
                assertThat(second.send(HttpRequest.newBuilder(URI.create(url("/api/v1/users/me"))).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
            } finally { socket.abort(); }
        }
    }
}
