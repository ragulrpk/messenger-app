package com.ctd.demo;

import com.ctd.demo.auth.AdminPasswordResetService.ResetRequest;
import com.ctd.demo.config.LoginRateLimiter;
import com.ctd.demo.user.*;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
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

/** 2026-09-23: Real session/CSRF checks for administrator-only recovery; all accounts are synthetic. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:admin_resets;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminPasswordResetTests {
    private static final String ADMIN = "AdministratorPassword!12";
    private static final String OLD = "OriginalUserPassword!12";
    private static final String NEW = "ReplacementPassword!12";
    private static final String PATH = "/api/v1/users/admin-password-reset";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @MockitoBean LoginRateLimiter limiter;

    @BeforeEach void seed() {
        users.deleteAll();
        users.save(new AppUser("ragul", "Administrator", encoder.encode(ADMIN)));
        users.save(new AppUser("member", "Member", encoder.encode(OLD)));
        users.save(new AppUser("other", "Other", encoder.encode(OLD)));
        when(limiter.allow(anyString())).thenReturn(true);
        when(limiter.window()).thenReturn(Duration.ofMinutes(1));
    }
    private String csrf(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/v1/users/csrf").session(session)).andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
    private MockHttpSession login(String username, String password) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/users/login").session(session).header("X-CSRF-TOKEN", csrf(session))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.administrator").value("ragul".equals(username)));
        return session;
    }
    private String body(String target, String admin, String next, String confirm) {
        return "{\"username\":\"" + target + "\",\"currentPassword\":\"" + admin + "\",\"newPassword\":\""
                + next + "\",\"confirmPassword\":\"" + confirm + "\",\"administrator\":true}";
    }
    private String stored(String username) { return users.findByUsername(username).orElseThrow().getPasswordHash(); }

    @Test void resetsOnlyTargetAndInvalidatesTheirSessionsWhileAdministratorStaysSignedIn() throws Exception {
        var admin = login("ragul", ADMIN);
        var member = login("member", OLD);
        String otherHash = stored("other");
        String adminHash = stored("ragul");
        mvc.perform(post(PATH).session(admin).header("X-CSRF-TOKEN", csrf(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(body(" MEMBER ", ADMIN, NEW, NEW))).andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(encoder.matches(NEW, stored("member"))).isTrue();
        assertThat(encoder.matches(OLD, stored("member"))).isFalse();
        assertThat(stored("other")).isEqualTo(otherHash);
        assertThat(stored("ragul")).isEqualTo(adminHash);
        mvc.perform(get("/api/v1/users/me").session(member)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.user.administrator").value(true));
        login("member", NEW);
        var anonymous = new MockHttpSession();
        mvc.perform(post("/api/v1/users/login").session(anonymous).header("X-CSRF-TOKEN", csrf(anonymous))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"member\",\"password\":\"" + OLD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void requiresAdminSessionCsrfAndRateLimitAllowanceEvenWithForgedAdminFlag() throws Exception {
        String hash = stored("member");
        for (var session : new MockHttpSession[]{new MockHttpSession(), login("other", OLD)}) {
            mvc.perform(post(PATH).session(session).header("X-CSRF-TOKEN", csrf(session)).contentType(MediaType.APPLICATION_JSON)
                    .content(body("member", ADMIN, NEW, NEW)))
                    .andExpect(status().is(session.getAttribute("SPRING_SECURITY_CONTEXT") == null ? 401 : 403));
        }
        var admin = login("ragul", ADMIN);
        mvc.perform(post(PATH).session(admin).contentType(MediaType.APPLICATION_JSON).content(body("member", ADMIN, NEW, NEW)))
                .andExpect(status().isForbidden());
        when(limiter.allow("admin-password-reset:ragul")).thenReturn(false);
        mvc.perform(post(PATH).session(admin).header("X-CSRF-TOKEN", csrf(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(body("member", ADMIN, NEW, NEW))).andExpect(status().isTooManyRequests());
        assertThat(stored("member")).isEqualTo(hash);
    }

    @Test void rejectsWrongAdministratorPasswordInvalidTargetsAndInvalidPasswordsWithoutMutation() throws Exception {
        var admin = login("ragul", ADMIN);
        String hash = stored("member");
        for (String[] values : new String[][] {
                {"member", "wrong", NEW, NEW, "currentPassword"},
                {"ragul", ADMIN, NEW, NEW, "username"},
                {"missing", ADMIN, NEW, NEW, "username"},
                {"member", ADMIN, "short", "short", "newPassword"},
                {"member", ADMIN, NEW, "mismatch", "confirmPassword"},
                {"member", ADMIN, OLD, OLD, "newPassword"},
                {"member", ADMIN, ADMIN, ADMIN, "newPassword"},
                {"member", ADMIN, "é".repeat(37), "é".repeat(37), "newPassword"} }) {
            mvc.perform(post(PATH).session(admin).header("X-CSRF-TOKEN", csrf(admin)).contentType(MediaType.APPLICATION_JSON)
                    .content(body(values[0], values[1], values[2], values[3])))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors." + values[4]).exists());
        }
        assertThat(stored("member")).isEqualTo(hash);
        assertThat(new ResetRequest("member", ADMIN, NEW, NEW).toString()).doesNotContain(ADMIN, NEW);
    }
}
