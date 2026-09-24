package com.ctd.demo;

import com.ctd.demo.auth.*;
import com.ctd.demo.config.LoginRateLimiter;
import com.ctd.demo.user.*;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 2026-09-23: Verify self-service updates, CSRF, old-session invalidation, and concurrent changes. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:password_changes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordChangeTests {
    private static final String OLD = "OriginalPassword!12";
    private static final String NEW = "ReplacementPassword!12";
    private static final String PATH = "/api/v1/users/password";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired PasswordChangeService passwords;
    @MockitoBean LoginRateLimiter limiter;

    @BeforeEach
    void seed() {
        users.deleteAll();
        users.save(new AppUser("owner", "Owner", encoder.encode(OLD)));
        users.save(new AppUser("other", "Other", encoder.encode(OLD)));
        when(limiter.allow(anyString())).thenReturn(true);
        when(limiter.window()).thenReturn(Duration.ofSeconds(60));
    }

    private String csrf(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/v1/users/csrf").session(session)).andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
    private MockHttpSession login(String password) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/users/login").session(session).header("X-CSRF-TOKEN", csrf(session))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"owner\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        return session;
    }
    private String body(String current, String replacement, String confirmation) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + replacement
                + "\",\"confirmPassword\":\"" + confirmation + "\",\"username\":\"other\"}";
    }

    @Test
    void changesOnlySessionAccountAndRejectsAllOlderSessions() throws Exception {
        var first = login(OLD);
        var second = login(OLD);
        String otherHash = users.findByUsername("other").orElseThrow().getPasswordHash();
        mvc.perform(post(PATH).session(first).header("X-CSRF-TOKEN", csrf(first))
                .contentType(MediaType.APPLICATION_JSON).content(body(OLD, NEW, NEW)))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(first.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/users/me").session(second)).andExpect(status().isUnauthorized());
        String stored = users.findByUsername("owner").orElseThrow().getPasswordHash();
        assertThat(stored).isNotEqualTo(NEW);
        assertThat(encoder.matches(NEW, stored)).isTrue();
        assertThat(encoder.matches(OLD, stored)).isFalse();
        assertThat(users.findByUsername("other").orElseThrow().getPasswordHash()).isEqualTo(otherHash);
        mvc.perform(get("/api/v1/users/me").session(login(NEW))).andExpect(status().isOk());
        var anonymous = new MockHttpSession();
        mvc.perform(post("/api/v1/users/login").session(anonymous).header("X-CSRF-TOKEN", csrf(anonymous))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"owner\",\"password\":\"" + OLD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidInputWithoutChangingHashOrEndingSession() throws Exception {
        var session = login(OLD);
        String original = users.findByUsername("owner").orElseThrow().getPasswordHash();
        for (String[] values : new String[][] {
                {"wrong", NEW, NEW, "currentPassword"},
                {OLD, "short", "short", "newPassword"},
                {OLD, NEW, "different", "confirmPassword"},
                {OLD, OLD, OLD, "newPassword"},
                {OLD, "é".repeat(37), "é".repeat(37), "newPassword"},
                {"a".repeat(73), NEW, NEW, "currentPassword"} }) {
            mvc.perform(post(PATH).session(session).header("X-CSRF-TOKEN", csrf(session))
                    .contentType(MediaType.APPLICATION_JSON).content(body(values[0], values[1], values[2])))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors." + values[3]).exists());
        }
        assertThat(users.findByUsername("owner").orElseThrow().getPasswordHash()).isEqualTo(original);
        mvc.perform(get("/api/v1/users/me").session(session)).andExpect(status().isOk());
        assertThat(new PasswordChangeRequest(OLD, NEW, NEW).toString()).doesNotContain(OLD, NEW);
    }

    @Test
    void requiresAuthenticationCsrfAndRateLimitAllowance() throws Exception {
        var anonymous = new MockHttpSession();
        mvc.perform(post(PATH).session(anonymous).header("X-CSRF-TOKEN", csrf(anonymous))
                .contentType(MediaType.APPLICATION_JSON).content(body(OLD, NEW, NEW)))
                .andExpect(status().isUnauthorized());
        var session = login(OLD);
        mvc.perform(post(PATH).session(session).contentType(MediaType.APPLICATION_JSON).content(body(OLD, NEW, NEW)))
                .andExpect(status().isForbidden());
        when(limiter.allow("password-change:owner")).thenReturn(false);
        mvc.perform(post(PATH).session(session).header("X-CSRF-TOKEN", csrf(session))
                .contentType(MediaType.APPLICATION_JSON).content(body(OLD, NEW, NEW)))
                .andExpect(status().isTooManyRequests());
        assertThat(encoder.matches(OLD, users.findByUsername("owner").orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void concurrentChangesCannotOverwriteUsingAnOlderCredentialStamp() throws Exception {
        String stamp = CredentialStamp.of(users.findByUsername("owner").orElseThrow().getPasswordHash());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = pool.invokeAll(java.util.List.<java.util.concurrent.Callable<Integer>>of(
                    () -> attempt(stamp, NEW), () -> attempt(stamp, NEW + "2")));
            assertThat(futures.get(0).get() + futures.get(1).get()).isEqualTo(1);
        }
    }
    private int attempt(String stamp, String replacement) {
        try {
            passwords.change("owner", stamp, new PasswordChangeRequest(OLD, replacement, replacement));
            return 1;
        } catch (org.springframework.security.core.AuthenticationException expected) { return 0; }
    }
}
