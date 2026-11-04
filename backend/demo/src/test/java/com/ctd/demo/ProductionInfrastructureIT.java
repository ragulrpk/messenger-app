package com.ctd.demo;

import com.ctd.demo.auth.CredentialStamp;
import com.ctd.demo.chat.ChatNotifications;
import com.ctd.demo.chat.ChatPush;
import com.ctd.demo.chat.ChatService;
import com.ctd.demo.chat.WebPushTransport;
import com.ctd.demo.config.LoginRateLimiter;
import com.ctd.demo.config.RedisLoginRateLimiter;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = "logging.structured.format.console=logstash")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductionInfrastructureIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("messenger").withUsername("messenger").withPassword("integration-secret");
    /** A real SMTP server that catches mail, with an HTTP API to read it. */
    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.21"))
            .withExposedPorts(1025, 8025);
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", POSTGRES::getUsername);
        properties.add("spring.flyway.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        properties.add("app.cors.allowed-origins", () -> "https://messenger.example.test");
        properties.add("spring.mail.host", MAILPIT::getHost);
        properties.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    @Autowired JdbcTemplate db;
    @Autowired Flyway flyway;
    @Autowired UserRepository users;
    @Autowired ChatService chat;
    @Autowired LoginRateLimiter limiter;
    @Autowired StringRedisTemplate redis;
    @Autowired ChatNotifications notifications;
    @Autowired TransactionTemplate transactions;
    @Autowired ChatPush push;
    @MockitoBean WebPushTransport transport;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired org.springframework.security.core.session.SessionRegistry registry;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    @Test
    @Order(1)
    void containersStartMigrationsValidateAndReadinessIsUp() {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(REDIS.isRunning()).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("20");
        assertThat(db.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(limiter.allow("startup-probe")).isTrue();
    }

    @Test
    @Order(2)
    void historyUsesCoveringIndexAndConcurrentOperationsRemainIdempotent() throws Exception {
        AppUser alice = users.save(new AppUser("integration-alice", "Integration Alice", "unused"));
        AppUser bob = users.save(new AppUser("integration-bob", "Integration Bob", "unused"));
        UUID conversation;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> chat.direct(alice.getUsername(), bob.getId()).id());
            var second = pool.submit(() -> chat.direct(bob.getUsername(), alice.getId()).id());
            conversation = first.get();
            assertThat(second.get()).isEqualTo(conversation);
            UUID retryId = UUID.randomUUID();
            var send1 = pool.submit(() -> chat.send(alice.getUsername(), conversation, retryId, "same message"));
            var send2 = pool.submit(() -> chat.send(alice.getUsername(), conversation, retryId, "same message"));
            assertThat(send1.get().id()).isEqualTo(send2.get().id());
        }

        for (int start = 0; start < 5_000; start += 500) {
            int batchStart = start;
            db.batchUpdate("INSERT INTO chat_messages(id,conversation_id,sender_id,client_id,body,sent_at) VALUES (?,?,?,?,?,?)",
                    java.util.stream.IntStream.range(batchStart, batchStart + 500).boxed().toList(), 500,
                    (statement, number) -> {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, conversation);
                        statement.setObject(3, alice.getId());
                        statement.setObject(4, UUID.randomUUID());
                        statement.setString(5, "history " + number);
                        statement.setTimestamp(6, Timestamp.from(Instant.now()));
                    });
        }
        db.execute("ANALYZE chat_messages");
        String plan = String.join("\n", db.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS) SELECT id,sequence,sender_id,client_id,body,sent_at FROM chat_messages "
                        + "WHERE conversation_id=? ORDER BY sequence DESC LIMIT 51", String.class, conversation));
        String indexDefinition = db.queryForObject(
                "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indexrelid='chat_messages_history_idx'::regclass",
                String.class);
        assertThat(plan).contains("Execution Time:");
        assertThat(indexDefinition).contains("conversation_id", "sequence DESC", "INCLUDE", "body", "sent_at");

        // The list preview skips messages deleted for the viewer without reading the whole history.
        var newest = db.queryForList("SELECT id FROM chat_messages WHERE conversation_id=? ORDER BY sequence DESC LIMIT 2",
                UUID.class, conversation);
        chat.delete(bob.getUsername(), newest.getFirst(), false);
        assertThat(chat.conversation(bob.getUsername(), conversation).lastMessage().id()).isEqualTo(newest.get(1));
        assertThat(chat.conversation(alice.getUsername(), conversation).lastMessage().id()).isEqualTo(newest.getFirst());
        String previewPlan = String.join("\n", db.queryForList(
                "EXPLAIN (ANALYZE, COSTS OFF) SELECT shown.sequence FROM chat_messages shown WHERE shown.conversation_id=? "
                        + "AND shown.sequence>0 AND NOT EXISTS (SELECT 1 FROM chat_message_hidden h WHERE h.message_id=shown.id "
                        + "AND h.user_id=?) ORDER BY shown.sequence DESC LIMIT 1", String.class, conversation, bob.getId()));
        // Newest-first index walk that stops at the first shown message; never an aggregate over every row.
        assertThat(previewPlan).startsWith("Limit").containsPattern("Index (Only )?Scan Backward using \\w+ on chat_messages shown")
                .doesNotContain("Aggregate", "Seq Scan on chat_messages");
    }

    @Test
    @Order(3)
    void sendsDoNotWaitForEachOtherAndNotificationsAreNeverSkipped() throws Exception {
        AppUser alice = users.save(new AppUser("notify-alice", "Notify Alice", "unused"));
        AppUser bob = users.save(new AppUser("notify-bob", "Notify Bob", "unused"));
        AppUser carol = users.save(new AppUser("notify-carol", "Notify Carol", "unused"));
        UUID fromAlice = chat.direct(alice.getUsername(), carol.getId()).id();
        UUID fromBob = chat.direct(bob.getUsername(), carol.getId()).id();
        long start = notifications.feed(carol.getUsername(), null).cursor();
        var sent = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                // Alice's send starts first, so it has the older transaction ID, and stays uncommitted.
                var slow = pool.submit(() -> transactions.executeWithoutResult(status -> {
                    chat.send(alice.getUsername(), fromAlice, UUID.randomUUID(), "first, committed last");
                    sent.countDown();
                    awaitQuietly(finish);
                }));
                assertThat(sent.await(10, TimeUnit.SECONDS)).isTrue();
                // A send in another conversation commits while Alice's is still open: there is no shared lock.
                pool.submit(() -> chat.send(bob.getUsername(), fromBob, UUID.randomUUID(), "second, committed first"))
                        .get(10, TimeUnit.SECONDS);
                // Bob's notification must wait: handing it out would move Carol's cursor past Alice's.
                var early = notifications.feed(carol.getUsername(), start);
                assertThat(early.notifications()).isEmpty();
                assertThat(early.pending()).isTrue();
                finish.countDown();
                slow.get(10, TimeUnit.SECONDS);
                var settled = notifications.feed(carol.getUsername(), early.cursor());
                assertThat(settled.notifications()).extracting(ChatNotifications.Item::body)
                        .containsExactly("first, committed last", "second, committed first");
                assertThat(settled.pending()).isFalse();
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    @Order(4)
    void pushIsSentOutsideTransactionsUnderALease() throws Exception {
        AppUser dana = users.save(new AppUser("push-dana", "Push Dana", "unused"));
        AppUser erin = users.save(new AppUser("push-erin", "Push Erin", "unused"));
        UUID conversation = chat.direct(dana.getUsername(), erin.getId()).id();
        db.update("INSERT INTO chat_push_subscriptions(id,user_id,session_id,credential_stamp,endpoint,public_key,auth_secret,"
                        + "cursor_position,retry_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)", "erin-device", erin.getId(), "session",
                CredentialStamp.of("unused"), "https://fcm.googleapis.com/erin", "key", "auth", notifications.position(),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        chat.send(dana.getUsername(), conversation, UUID.randomUUID(), "Background message");
        // Push goes only to a device whose session is still alive; signing in registers it.
        registry.registerNewSession("session", "push-erin");
        when(transport.enabled()).thenReturn(true);
        when(transport.send(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // Another replica would skip the leased row: it can be read and updated without waiting.
            assertThat(db.queryForObject("SELECT retry_at FROM chat_push_subscriptions WHERE id='erin-device' FOR UPDATE NOWAIT",
                    Timestamp.class).toInstant()).isAfter(Instant.now());
            return 201;
        });
        push.deliver();
        verify(transport, times(1)).send(anyString(), anyString(), anyString(), any());
        // The outcome matched this worker's lease exactly (microsecond timestamps) and was recorded.
        assertThat(db.queryForObject("SELECT cursor_position FROM chat_push_subscriptions WHERE id='erin-device'", Long.class))
                .isEqualTo(db.queryForObject("SELECT max(position) FROM chat_notifications WHERE user_id=?", Long.class, erin.getId()));
        when(transport.enabled()).thenReturn(false);
    }

    @Test
    @Order(5)
    void administratorsSignInWithTheirPassword() throws Exception {
        var admin = new AppUser("mail-admin", "Mail Admin", encoder.encode("AdministratorPassword!12"));
        admin.setDetails(null, "mail-admin@dept.in", null, null);
        admin.setAdministrator(true);
        users.save(admin);
        var session = new org.springframework.mock.web.MockHttpSession();
        String csrf = com.jayway.jsonpath.JsonPath.read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/users/csrf").session(session)).andReturn().getResponse().getContentAsString(), "$.token");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/users/admin-login")
                        .session(session).header("X-CSRF-TOKEN", csrf).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail-admin\",\"password\":\"AdministratorPassword!12\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.user.administrator").value(true));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @Order(6)
    void redisOutageFailsClosed() {
        RedisLoginRateLimiter redisLimiter = new RedisLoginRateLimiter(redis, 20, Duration.ofSeconds(60));
        REDIS.stop();
        assertThatThrownBy(() -> redisLimiter.allow("integration-client-after-stop"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Order(7)
    void postgresOutageSurfacesAsDataAccessFailure() {
        POSTGRES.stop();
        assertThatThrownBy(() -> db.queryForObject("SELECT 1", Integer.class))
                .isInstanceOf(DataAccessException.class);
    }
}
