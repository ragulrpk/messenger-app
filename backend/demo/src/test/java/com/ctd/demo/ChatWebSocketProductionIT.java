package com.ctd.demo;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 2026-09-23: Reuse the real upgrade/security/delivery tests with the PRODUCTION registry and Redis bus. */
@Testcontainers
@ActiveProfiles(value = "websocket-production-it", inheritProfiles = false)
class ChatWebSocketProductionIT extends ChatWebSocketTests {
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
