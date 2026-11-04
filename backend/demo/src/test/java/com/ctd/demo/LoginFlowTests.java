package com.ctd.demo;

import com.ctd.demo.auth.CodeMailer;
import com.ctd.demo.auth.LoginOrchestrationService;
import com.ctd.demo.config.LoginRateLimiter;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginFlowTests {
    private static final String PASSWORD = "EmployeePassword!12";
    private static final String ADMIN = "AdministratorPassword!12";
    private static final String TEMP = "TemporaryPassword!12";
    private static final String NEW = "ChosenPassword!1234";
    private static final String USERS = "/api/v1/users";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate db;
    @MockitoBean LoginRateLimiter limiter;
    /** Emailed codes are switched off; no step may send one. */
    @MockitoBean CodeMailer mailer;

    @BeforeEach void seed() {
        clean();
        users.deleteAll();
        account("gpf001", "GPF001", "emp@dept.in", false, false, PASSWORD);
        account("boss", null, "boss@dept.in", true, false, ADMIN);
        account("fresh", null, "fresh@dept.in", false, true, TEMP);
        account("nomail", null, null, false, true, TEMP);
        account("newboss", null, null, true, true, TEMP);
        when(limiter.allow(anyString())).thenReturn(true);
        when(limiter.window()).thenReturn(Duration.ofMinutes(1));
    }
    @AfterEach void clean() {
        db.update("DELETE FROM one_time_codes");
        db.update("DELETE FROM sign_in_events");
        db.update("DELETE FROM account_audit");
    }
    private void account(String loginId, String gpf, String email, boolean admin, boolean temporary, String password) {
        var user = new AppUser(loginId, loginId, encoder.encode(password));
        user.setDetails(gpf, email, null, null);
        user.setAdministrator(admin);
        if (temporary) user.requirePasswordChange();
        users.save(user);
    }
    private String csrf(MockHttpSession session) throws Exception {
        return JsonPath.read(mvc.perform(get(USERS + "/csrf").session(session)).andReturn().getResponse().getContentAsString(), "$.token");
    }
    private ResultActions post(MockHttpSession session, String path, String json) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(USERS + path).session(session)
                .header("X-CSRF-TOKEN", csrf(session)).contentType(MediaType.APPLICATION_JSON).content(json));
    }
    private ResultActions login(MockHttpSession session, String path, String loginId, String password) throws Exception {
        return post(session, path, "{\"username\":\"" + loginId + "\",\"password\":\"" + password + "\"}");
    }
    private static String newPassword(String password) {
        return "{\"newPassword\":\"" + password + "\",\"confirmPassword\":\"" + password + "\"}";
    }
    private ResultActions me(MockHttpSession session) throws Exception {
        return mvc.perform(get(USERS + "/me").session(session));
    }

    @Test void employeesSignInWithTheirGpfNumberAndPassword() throws Exception {
        var session = new MockHttpSession();
        login(session, "/login", " gpf001 ", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("gpf001")).andExpect(jsonPath("$.status").doesNotExist());
        me(session).andExpect(status().isOk());
        login(new MockHttpSession(), "/login", "GPF001", "wrong").andExpect(status().isUnauthorized());
        assertThat(db.queryForList("SELECT outcome FROM sign_in_events ORDER BY occurred_at", String.class))
                .containsExactly("SIGNED_IN", "WRONG_PASSWORD");
        verifyNoInteractions(mailer);
    }

    @Test void administratorsUseTheirOwnPageAndSignInWithTheirPassword() throws Exception {
        login(new MockHttpSession(), "/login", "boss", ADMIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USE_ADMIN_LOGIN"));
        login(new MockHttpSession(), "/admin-login", "gpf001", PASSWORD).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMINISTRATOR"));
        login(new MockHttpSession(), "/admin-login", "boss", "wrong").andExpect(status().isUnauthorized());
        var session = new MockHttpSession();
        login(session, "/admin-login", "boss", ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist()).andExpect(jsonPath("$.user.administrator").value(true));
        me(session).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/people").session(session)).andExpect(status().isOk());
        verifyNoInteractions(mailer);
    }

    @Test void aTemporaryPasswordNeedsANewPassword() throws Exception {
        var session = new MockHttpSession();
        login(session, "/login", "fresh", TEMP).andExpect(jsonPath("$.status").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));
        me(session).andExpect(status().isUnauthorized());
        post(session, "/login/verify", newPassword("short")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        post(session, "/login/verify", newPassword(TEMP)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        post(session, "/login/verify", newPassword(NEW)).andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value("fresh"));
        me(session).andExpect(status().isOk());
        assertThat(users.findByUsername("fresh").orElseThrow().isMustChangePassword()).isFalse();
        login(new MockHttpSession(), "/login", "fresh", TEMP).andExpect(status().isUnauthorized());
        login(new MockHttpSession(), "/login", "fresh", NEW).andExpect(status().isOk()).andExpect(jsonPath("$.user").exists());
        // No email address is needed any more.
        var noMail = new MockHttpSession();
        login(noMail, "/login", "nomail", TEMP).andExpect(jsonPath("$.status").value("PASSWORD_CHANGE_REQUIRED"));
        post(noMail, "/login/verify", newPassword(NEW)).andExpect(status().isOk());
        verifyNoInteractions(mailer);
    }

    @Test void anAdministratorWithATemporaryPasswordGetsAnAdministratorSession() throws Exception {
        var session = new MockHttpSession();
        login(session, "/admin-login", "newboss", TEMP).andExpect(jsonPath("$.status").value("PASSWORD_CHANGE_REQUIRED"));
        post(session, "/login/verify", newPassword(NEW)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.administrator").value(true));
        mvc.perform(get("/api/v1/admin/people").session(session)).andExpect(status().isOk());
    }

    @Test void forgottenPasswordsAreResetWithANewPassword() throws Exception {
        var signedIn = new MockHttpSession();
        login(signedIn, "/login", "gpf001", PASSWORD).andExpect(status().isOk());
        post(new MockHttpSession(), "/password-reset/start", "{\"username\":\"nobody\"}").andExpect(status().isNotFound());
        // Completing needs a reset started in the same session.
        post(new MockHttpSession(), "/password-reset/complete", newPassword(NEW)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_PENDING_SIGN_IN"));
        var session = new MockHttpSession();
        post(session, "/password-reset/start", "{\"username\":\"GPF001\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSWORD_CHANGE_REQUIRED"));
        post(session, "/password-reset/complete", "{\"newPassword\":\"" + NEW + "\",\"confirmPassword\":\"different!12345\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.confirmPassword").exists());
        post(session, "/password-reset/complete", newPassword(NEW)).andExpect(status().isNoContent());
        // Every session of the account ends, and the reset does not sign anyone in.
        me(signedIn).andExpect(status().isUnauthorized());
        me(session).andExpect(status().isUnauthorized());
        login(new MockHttpSession(), "/login", "gpf001", PASSWORD).andExpect(status().isUnauthorized());
        login(new MockHttpSession(), "/login", "gpf001", NEW).andExpect(status().isOk());
        // A finished reset cannot be repeated.
        post(session, "/password-reset/complete", newPassword("AnotherPassword!123")).andExpect(status().isBadRequest());
        verifyNoInteractions(mailer);
    }

    @Test void signingInOnAnotherDeviceEndsTheFirstSession() throws Exception {
        var first = new MockHttpSession();
        login(first, "/login", "gpf001", PASSWORD).andExpect(status().isOk());
        var second = new MockHttpSession();
        login(second, "/login", "gpf001", PASSWORD).andExpect(status().isOk());
        me(first).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_ENDED"));
        me(second).andExpect(status().isOk());
    }

    @Test void sessionsEndAfterFiveMinutesWithoutRealUse() throws Exception {
        var session = new MockHttpSession();
        login(session, "/login", "gpf001", PASSWORD).andExpect(status().isOk());
        long started = (Long) session.getAttribute(LoginOrchestrationService.LAST_ACTIVITY);
        // Background requests do not count as use.
        Thread.sleep(5);
        mvc.perform(get("/api/v1/chat/conversations").session(session)).andExpect(status().isOk());
        assertThat(session.getAttribute(LoginOrchestrationService.LAST_ACTIVITY)).isEqualTo(started);
        post(session, "/activity", "{}").andExpect(status().isNoContent());
        assertThat((Long) session.getAttribute(LoginOrchestrationService.LAST_ACTIVITY)).isGreaterThan(started);
        session.setAttribute(LoginOrchestrationService.LAST_ACTIVITY, System.currentTimeMillis() - Duration.ofMinutes(5).toMillis());
        me(session).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_IDLE"));
        assertThat(session.isInvalid()).isTrue();
    }
}
