package com.ctd.demo;

import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import com.ctd.demo.auth.SessionRevocationService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionRevocationService sessionRevocation;
    private static final String LOGIN = "/api/v1/users/login";
    private static final String VALID = "{\"username\":\" Taylor \",\"password\":\"CorrectPassword!\"}";

    @BeforeEach
    void seed() {
        users.deleteAll();
        users.save(new AppUser("taylor", "Taylor Brooks", encoder.encode("CorrectPassword!")));
    }

    private MvcResult csrf(MockHttpSession session) throws Exception {
        var request = get("/api/v1/users/csrf");
        if (session != null) request.session(session);
        return mvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private String token(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    @Test
    void loginRotatesSessionPersistsUserAndLogoutInvalidatesSession() throws Exception {
        var csrf = csrf(null);
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        String originalId = session.getId();
        mvc.perform(post(LOGIN).session(session).header("X-CSRF-TOKEN", token(csrf))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("Taylor Brooks"))
                .andExpect(jsonPath("$.user.username").value("taylor"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        assertThat(session.getId()).isNotEqualTo(originalId);
        mvc.perform(get("/api/v1/users/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/users/logout").session(session).header("X-CSRF-TOKEN", token(csrf)))
                .andExpect(status().isForbidden());
        var refreshed = csrf(session);
        mvc.perform(post("/api/v1/users/logout").session(session).header("X-CSRF-TOKEN", token(refreshed)))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void accountWideRevocationExpiresAuthenticatedSessions() throws Exception {
        var csrf = csrf(null);
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        mvc.perform(post(LOGIN).session(session).header("X-CSRF-TOKEN", token(csrf))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk());
        sessionRevocation.revokeAll("taylor");
        mvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    void missingCsrfIsRejected() throws Exception {
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
    }

    @Test
    void badPasswordAndUnknownAccountHaveTheSameSafeResponse() throws Exception {
        var csrf = csrf(null);
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        mvc.perform(post(LOGIN).session(session).header("X-CSRF-TOKEN", token(csrf))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"unknown\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Username or password is incorrect."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        mvc.perform(post(LOGIN).session(session).header("X-CSRF-TOKEN", token(csrf))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"taylor\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Username or password is incorrect."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        mvc.perform(get("/api/v1/users/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidInputAndMalformedJsonAreRejected() throws Exception {
        var csrf = csrf(null);
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        mvc.perform(post(LOGIN).session(session).header("X-CSRF-TOKEN", token(csrf))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").value("Enter your username."))
                .andExpect(jsonPath("$.fieldErrors.password").value("Enter your password."));
        mvc.perform(post(LOGIN).session(session).header("X-CSRF-TOKEN", token(csrf))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\" \",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").value("Enter your username."))
                .andExpect(jsonPath("$.fieldErrors.password").doesNotExist());
        mvc.perform(post(LOGIN).session(session).header("X-CSRF-TOKEN", token(csrf))
                        .contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void disabledAccountCannotLogin() throws Exception {
        jdbc.update("UPDATE app_users SET enabled = false WHERE username = ?", "taylor");
        var csrf = csrf(null);
        mvc.perform(post(LOGIN).session((MockHttpSession) csrf.getRequest().getSession(false))
                        .header("X-CSRF-TOKEN", token(csrf)).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsPasswordsBeyondBcryptByteLimitAndUnsupportedContentType() throws Exception {
        var csrf = csrf(null);
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        String oversized = "{\"username\":\"taylor\",\"password\":\"" + "é".repeat(37) + "\"}";
        mvc.perform(post(LOGIN).session(session).header("X-CSRF-TOKEN", token(csrf))
                        .contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(LOGIN).session(session).header("X-CSRF-TOKEN", token(csrf))
                        .contentType(MediaType.TEXT_PLAIN).content(VALID))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void allowsConfiguredOriginAndRejectsUntrustedOrigin() throws Exception {
        mvc.perform(options(LOGIN).header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options(LOGIN).header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void passwordsAreHashedAndHealthIsPublic() throws Exception {
        var user = users.findByUsername("taylor").orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo("CorrectPassword!");
        assertThat(encoder.matches("CorrectPassword!", user.getPasswordHash())).isTrue();
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
