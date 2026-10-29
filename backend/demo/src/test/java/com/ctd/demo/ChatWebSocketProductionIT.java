package com.ctd.demo;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import com.ctd.demo.config.RedisLoginRateLimiter;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import com.ctd.demo.chat.ChatWebSocketHandler;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

/** 2026-09-23: Reuse the real upgrade/security/delivery tests with the PRODUCTION registry and Redis bus. */
@Testcontainers
@ActiveProfiles(value = "websocket-production-it", inheritProfiles = false)
@TestPropertySource(properties = "logging.structured.format.console=logstash")
class ChatWebSocketProductionIT extends ChatWebSocketTests {
    @Autowired RedisIndexedSessionRepository sessionRepository;
    @Autowired StringRedisTemplate redis;

    @Test
    void twoBackendsShareSessionsEventsAndRevocationAcrossRestart() throws Exception {
        try (var alice = login("ws-alice"); var bobClient = login("ws-bob");
             var eve = login("ws-eve")) {
            try (var second = secondBackend()) {
                int secondPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
                assertThat(secondPort).isNotEqualTo(port);
                assertThat(second.getBean(RedisIndexedSessionRepository.class)).isNotSameAs(sessionRepository);
                assertThat(get(alice, secondPort, "/api/v1/users/me").statusCode()).isEqualTo(200);
                assertThat(get(alice, secondPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
                var a = new Inbox(); var b = new Inbox(); var outsider = new Inbox();
                var wa = connect(alice, a, "http://localhost:5173");
                var wb = bobClient.newWebSocketBuilder().header("Origin", "http://localhost:5173")
                        .buildAsync(URI.create("ws://127.0.0.1:" + secondPort + "/api/v1/chat/events"), b)
                        .get(5, TimeUnit.SECONDS);
                var we = eve.newWebSocketBuilder().header("Origin", "http://localhost:5173")
                        .buildAsync(URI.create("ws://127.0.0.1:" + secondPort + "/api/v1/chat/events"), outsider)
                        .get(5, TimeUnit.SECONDS);
                try {
                    assertThat(a.next()).isEqualTo("ready");
                    assertThat(b.next()).isEqualTo("ready");
                    assertThat(outsider.next()).isEqualTo("ready");
                    // A CSRF token issued by A must work with the shared cookie on B.
                    var direct = post(alice, secondPort, "/api/v1/chat/conversations/direct",
                            "{\"userId\":\"" + bob + "\"}");
                    assertThat(direct.statusCode()).isEqualTo(200);
                    String conversation = JsonPath.read(direct.body(), "$.id");
                    assertThat(a.next()).isEqualTo("refresh");
                    assertThat(b.next()).isEqualTo("refresh");
                    String path = "/api/v1/chat/conversations/" + conversation + "/messages";
                    String typingPath = "/api/v1/chat/conversations/" + conversation + "/typing";
                    assertThat(post(alice, port, typingPath, "{\"active\":true}").statusCode()).isEqualTo(200);
                    assertThat(b.next()).isEqualTo("typing:" + conversation + ":true");
                    assertThat(post(alice, port, typingPath, "{\"active\":false}").statusCode()).isEqualTo(200);
                    assertThat(b.next()).isEqualTo("typing:" + conversation + ":false");
                    assertThat(post(bobClient, secondPort, typingPath, "{\"active\":true}").statusCode()).isEqualTo(200);
                    assertThat(a.next()).isEqualTo("typing:" + conversation + ":true");
                    assertThat(post(eve, secondPort, typingPath, "{\"active\":true}").statusCode()).isEqualTo(404);
                    assertThat(outsider.events.poll(300, TimeUnit.MILLISECONDS)).isNull();
                    String body = "{\"clientId\":\"" + UUID.randomUUID() + "\",\"text\":\"cross-instance\"}";
                    var sent = post(alice, port, path, body);
                    assertThat(sent.statusCode()).isEqualTo(200);
                    assertThat(a.next()).isEqualTo("refresh");
                    assertThat(b.next()).isEqualTo("refresh");
                    assertThat(outsider.events.poll(300, TimeUnit.MILLISECONDS)).isNull();
                    var retry = post(alice, secondPort, path, body);
                    assertThat(retry.statusCode()).isEqualTo(200);
                    assertThat((String) JsonPath.read(retry.body(), "$.id"))
                            .isEqualTo((String) JsonPath.read(sent.body(), "$.id"));
                    var history = get(bobClient, secondPort, path);
                    assertThat(history.statusCode()).isEqualTo(200);
                    assertThat((java.util.List<?>) JsonPath.read(history.body(), "$.messages")).hasSize(1);
                    // Replay the pre-logout cookie on B, rather than relying on browser cookie deletion.
                    var cookies = (java.net.CookieManager) alice.cookieHandler().orElseThrow();
                    String cookie = cookies.getCookieStore().getCookies().stream()
                            .map(c -> c.getName() + "=" + c.getValue())
                            .collect(java.util.stream.Collectors.joining("; "));
                    assertThat(post(alice, port, "/api/v1/users/logout", "").statusCode()).isEqualTo(204);
                    try (var replay = HttpClient.newHttpClient()) {
                        assertThat(replay.send(HttpRequest.newBuilder(endpoint(secondPort, "/api/v1/users/me"))
                                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString())
                                .statusCode()).isEqualTo(401);
                    }
                } finally { wa.abort(); wb.abort(); we.abort(); }
            }
            // B has a fresh application context, connection factory and socket registry after restart.
            try (var restarted = secondBackend()) {
                int restartedPort = ((ServletWebServerApplicationContext) restarted).getWebServer().getPort();
                assertThat(get(bobClient, restartedPort, "/api/v1/users/me").statusCode()).isEqualTo(200);
                var inbox = new Inbox();
                var socket = bobClient.newWebSocketBuilder().header("Origin", "http://localhost:5173")
                        .buildAsync(URI.create("ws://127.0.0.1:" + restartedPort + "/api/v1/chat/events"), inbox)
                        .get(5, TimeUnit.SECONDS);
                try {
                    assertThat(inbox.next()).isEqualTo("ready");
                    assertThat(post(bobClient, port, "/api/v1/users/password",
                            "{\"currentPassword\":\"test-password-123\",\"newPassword\":\"replacement-password-123\","
                                    + "\"confirmPassword\":\"replacement-password-123\"}").statusCode()).isEqualTo(204);
                    restarted.getBean(ChatWebSocketHandler.class).heartbeat();
                    assertThat(inbox.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
                    assertThat(get(bobClient, restartedPort, "/api/v1/users/me").statusCode()).isEqualTo(401);
                } finally { socket.abort(); }
            }
        }
    }

    private ConfigurableApplicationContext secondBackend() {
        return new SpringApplicationBuilder(DemoApplication.class).run(
                "--spring.profiles.active=websocket-production-it", "--server.port=0", "--server.address=127.0.0.1",
                "--DB_URL=" + POSTGRES.getJdbcUrl(), "--DB_USERNAME=" + POSTGRES.getUsername(),
                "--DB_PASSWORD=" + POSTGRES.getPassword(),
                "--MIGRATION_DB_URL=" + POSTGRES.getJdbcUrl(), "--MIGRATION_DB_USERNAME=" + POSTGRES.getUsername(),
                "--MIGRATION_DB_PASSWORD=" + POSTGRES.getPassword(),
                "--REDIS_URL=redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
                "--FRONTEND_ORIGIN=http://localhost:5173", "--server.servlet.session.cookie.secure=false",
                "--app.security.login-rate-limit.attempts=100", "--logging.structured.format.console=logstash");
    }

    private URI endpoint(int targetPort, String path) {
        return URI.create("http://127.0.0.1:" + targetPort + path);
    }

    private HttpResponse<String> get(HttpClient client, int targetPort, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(endpoint(targetPort, path)).timeout(Duration.ofSeconds(10))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, int targetPort, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(endpoint(targetPort, path)).timeout(Duration.ofSeconds(10))
                .header("X-CSRF-TOKEN", token(client)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void loginPersistsAnIndexedSessionInTheApplicationNamespace() throws Exception {
        String username = "redis-" + UUID.randomUUID();
        users.save(new com.ctd.demo.user.AppUser(username, "Redis Session Test",
                passwords.encode("test-password-123")));
        try (var client = login(username)) {
            var sessions = sessionRepository.findByPrincipalName(username);
            assertThat(sessions).hasSize(1);
            var session = sessions.values().iterator().next();
            String key = "messenger:session:sessions:" + session.getId();
            assertThat(redis.hasKey(key)).isTrue();
            assertThat(redis.getExpire(key)).isPositive();
            assertThat(sessionRepository.findById(session.getId())).isNotNull();
        }
    }

    @Test
    void separateLimiterInstancesShareCountersAndResetInRedis() {
        String key = "integration:" + UUID.randomUUID();
        var first = new RedisLoginRateLimiter(redis, 2, Duration.ofSeconds(60));
        var second = new RedisLoginRateLimiter(redis, 2, Duration.ofSeconds(60));
        try {
            assertThat(first.allow(key)).isTrue();
            assertThat(second.allow(key)).isTrue();
            assertThat(first.allow(key)).isFalse();
            second.clear(key);
            assertThat(first.allow(key)).isTrue();
        } finally {
            first.clear(key);
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("messenger_ws_test").withUsername("test").withPassword("test-only-password");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void productionInfrastructure(DynamicPropertyRegistry properties) {
        properties.add("DB_URL", POSTGRES::getJdbcUrl);
        properties.add("DB_USERNAME", POSTGRES::getUsername);
        properties.add("DB_PASSWORD", POSTGRES::getPassword);
        properties.add("REDIS_URL", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        properties.add("FRONTEND_ORIGIN", () -> "http://localhost:5173");
        // Only the disposable HTTP test server disables Secure cookies; production still requires HTTPS.
        properties.add("server.servlet.session.cookie.secure", () -> "false");
    }
}
