package com.ctd.demo;

import com.ctd.demo.chat.ChatService;
import com.ctd.demo.config.LoginRateLimiter;
import com.ctd.demo.config.RedisLoginRateLimiter;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = "logging.structured.format.console=logstash")
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductionInfrastructureIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("messenger").withUsername("messenger").withPassword("integration-secret");
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
    }

    @Autowired JdbcTemplate db;
    @Autowired Flyway flyway;
    @Autowired UserRepository users;
    @Autowired ChatService chat;
    @Autowired LoginRateLimiter limiter;
    @Autowired StringRedisTemplate redis;

    @Test
    @Order(1)
    void containersStartMigrationsValidateAndReadinessIsUp() {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(REDIS.isRunning()).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
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
    }

    @Test
    @Order(3)
    void redisOutageFailsClosed() {
        RedisLoginRateLimiter redisLimiter = new RedisLoginRateLimiter(redis, 20, Duration.ofSeconds(60));
        REDIS.stop();
        assertThatThrownBy(() -> redisLimiter.allow("integration-client-after-stop"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Order(4)
    void postgresOutageSurfacesAsDataAccessFailure() {
        POSTGRES.stop();
        assertThatThrownBy(() -> db.queryForObject("SELECT 1", Integer.class))
                .isInstanceOf(DataAccessException.class);
    }
}
