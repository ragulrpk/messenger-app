package com.ctd.demo;

import com.ctd.demo.admin.AdminService;
import com.ctd.demo.admin.EmployeeImport;
import com.ctd.demo.chat.ChatService;
import com.ctd.demo.config.AccountBootstrap;
import com.ctd.demo.config.LoginRateLimiter;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminConsoleTests {
    private static final String ADMIN = "AdministratorPassword!12";
    private static final String PASSWORD = "EmployeePassword!12";
    private static final String NEW = "ChosenPassword!1234";
    private static final String API = "/api/v1/admin";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate db;
    @Autowired ChatService chat;
    @Autowired EmployeeImport importer;
    @MockitoBean LoginRateLimiter limiter;
    UUID boss, member, other;

    @BeforeEach void seed() {
        clean();
        users.deleteAll();
        boss = account("boss", "boss@dept.in", true, ADMIN);
        member = account("member", "member@dept.in", false, PASSWORD);
        other = account("other", "other@dept.in", false, PASSWORD);
        when(limiter.allow(anyString())).thenReturn(true);
        when(limiter.window()).thenReturn(Duration.ofMinutes(1));
    }
    // Audit and history rows reference accounts, and other test classes delete every account between tests.
    @AfterEach void clean() {
        for (String table : List.of("account_audit", "one_time_codes", "sign_in_events", "chat_messages", "chat_members",
                "chat_conversations")) db.update("DELETE FROM " + table);
    }
    private UUID account(String loginId, String email, boolean admin, String password) {
        var user = new AppUser(loginId, loginId, encoder.encode(password));
        user.setDetails(null, email, null, null);
        user.setAdministrator(admin);
        return users.save(user).getId();
    }
    private String csrf(MockHttpSession session) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/v1/users/csrf").session(session)).andReturn().getResponse().getContentAsString(), "$.token");
    }
    private ResultActions send(MockHttpSession session, String path, String json) throws Exception {
        return mvc.perform(post(path).session(session).header("X-CSRF-TOKEN", csrf(session))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
    private ResultActions read(MockHttpSession session, String path) throws Exception {
        return mvc.perform(get(path).session(session));
    }
    /** Signs in through the administrator page with the password. */
    private MockHttpSession admin() throws Exception {
        var session = new MockHttpSession();
        send(session, "/api/v1/users/admin-login", "{\"username\":\"boss\",\"password\":\"" + ADMIN + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.administrator").value(true));
        return session;
    }
    private MockHttpSession employee(String loginId, String password) throws Exception {
        var session = new MockHttpSession();
        send(session, "/api/v1/users/login", "{\"username\":\"" + loginId + "\",\"password\":\"" + password + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.user").exists());
        return session;
    }

    @Test void administrationNeedsTheAdministratorSignIn() throws Exception {
        read(employee("member", PASSWORD), API + "/people").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMINISTRATOR"));
        // A session that did not come from the administrator page (here, a test login) is refused.
        mvc.perform(get(API + "/people").with(request -> {
            var processed = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("boss")
                    .postProcessRequest(request);
            processed.getSession().setAttribute(com.ctd.demo.auth.CredentialStamp.ATTRIBUTE,
                    com.ctd.demo.auth.CredentialStamp.of(users.findById(boss).orElseThrow().getPasswordHash()));
            return processed;
        })).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_SIGN_IN_REQUIRED"));
        read(admin(), API + "/people").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test void newPeopleStartWithATemporaryPasswordTheAdministratorCannotUse() throws Exception {
        var admin = admin();
        String body = "{\"gpfNumber\":\" tn-1234 \",\"name\":\"New Officer\",\"email\":\"New.Officer@Dept.in\",\"mobile\":\"+91 98765 43210\",\"cadre\":\"Deputy\"}";
        String temporary = JsonPath.read(send(admin, API + "/people", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("tn-1234")).andReturn().getResponse().getContentAsString(), "$.temporaryPassword");
        // GPF number, email and mobile must each be unique.
        send(admin, API + "/people", body).andExpect(status().isConflict());
        send(admin, API + "/people", "{\"username\":\"clerk\",\"name\":\"C\",\"email\":\"new.officer@dept.in\"}").andExpect(status().isConflict());
        send(admin, API + "/people", "{\"username\":\"clerk\",\"name\":\"C\",\"email\":\"c@dept.in\",\"mobile\":\"9876543210\"}")
                .andExpect(status().isConflict());
        send(admin, API + "/people", "{\"username\":\"clerk\",\"name\":\"C\",\"email\":\"not-an-email\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DETAILS"));
        read(admin, API + "/people?q=deputy").andExpect(jsonPath("$.items.length()").value(0));
        read(admin, API + "/people?q=98765").andExpect(jsonPath("$.items[0].gpfNumber").value("TN-1234"))
                .andExpect(jsonPath("$.items[0].email").value("new.officer@dept.in")).andExpect(jsonPath("$.items[0].mustChangePassword").value(true));
        // The temporary password only starts a sign-in that needs a new password.
        var session = new MockHttpSession();
        send(session, "/api/v1/users/login", "{\"username\":\"TN-1234\",\"password\":\"" + temporary + "\"}")
                .andExpect(jsonPath("$.status").value("PASSWORD_CHANGE_REQUIRED")).andExpect(jsonPath("$.passwordChangeRequired").value(true));
        read(session, "/api/v1/users/me").andExpect(status().isUnauthorized());
    }

    @Test void accountsAreEditedDisabledSignedOutAndReset() throws Exception {
        var admin = admin();
        var session = employee("member", PASSWORD);
        send(admin, API + "/people/" + member, "{\"name\":\" Member Renamed \",\"email\":\"member@dept.in\",\"mobile\":\"9123456789\",\"cadre\":\"Clerk\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Member Renamed")).andExpect(jsonPath("$.cadre").value("Clerk"));
        send(admin, API + "/people/" + other, "{\"name\":\"Other\",\"email\":\"member@dept.in\"}").andExpect(status().isConflict());
        send(admin, API + "/people/" + member + "/sign-out", "{}").andExpect(status().isOk());
        read(session, "/api/v1/users/me").andExpect(status().isUnauthorized());
        session = employee("member", PASSWORD);
        send(admin, API + "/people/" + member + "/enabled", "{\"enabled\":false}").andExpect(jsonPath("$.enabled").value(false));
        read(session, "/api/v1/users/me").andExpect(status().isUnauthorized());
        send(new MockHttpSession(), "/api/v1/users/login", "{\"username\":\"member\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isUnauthorized());
        send(admin, API + "/people/" + member + "/enabled", "{\"enabled\":true}").andExpect(status().isOk());
        session = employee("member", PASSWORD);
        // A required reset leaves a password nobody knows; the employee uses Forgot password.
        send(admin, API + "/people/" + member + "/password-reset", "{}").andExpect(jsonPath("$.mustChangePassword").value(true));
        read(session, "/api/v1/users/me").andExpect(status().isUnauthorized());
        send(new MockHttpSession(), "/api/v1/users/login", "{\"username\":\"member\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isUnauthorized());
        send(admin, API + "/people/" + boss + "/enabled", "{\"enabled\":false}").andExpect(status().isBadRequest());
        read(admin, API + "/people/" + member).andExpect(jsonPath("$.signIns[0].outcome").value("WRONG_PASSWORD"));
    }

    @Test void administratorsNeedAnEmailAndOneAlwaysRemains() throws Exception {
        var admin = admin();
        UUID noEmail = account("noemail", null, false, PASSWORD);
        send(admin, API + "/people/" + noEmail + "/administrator", "{\"administrator\":true}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_EMAIL"));
        send(admin, API + "/people/" + boss + "/administrator", "{\"administrator\":false}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAST_ADMINISTRATOR"));
        var memberSession = employee("member", PASSWORD);
        send(admin, API + "/people/" + member + "/administrator", "{\"administrator\":true}").andExpect(jsonPath("$.administrator").value(true));
        // The role decides the sign-in page, so the member's current session ends.
        read(memberSession, "/api/v1/users/me").andExpect(status().isUnauthorized());
        send(new MockHttpSession(), "/api/v1/users/login", "{\"username\":\"member\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USE_ADMIN_LOGIN"));
        read(admin, API + "/audit").andExpect(jsonPath("$[0].action").value("ADMINISTRATOR_GRANTED"))
                .andExpect(jsonPath("$[0].actor").value("boss")).andExpect(jsonPath("$[0].target").value("member"));
        read(admin, API + "/sign-ins").andExpect(jsonPath("$[0].outcome").exists());
    }

    @Test void bulkAddSkipsDuplicatesAndInvalidRowsWithoutPasswords() throws Exception {
        var admin = admin();
        String csv = "gpf_number,username,name,email,mobile,cadre\n"
                + "GPF100,,Asha,asha@dept.in,9000000001,Deputy\n"
                + ",typist1,\"Bala, K\",bala@dept.in,,Typist\n"
                + "GPF101,,Dup Email,asha@dept.in,9000000002,\n"
                + "GPF102,,Existing,member@dept.in,,\n"
                + "GPF103,,Bad Mobile,bad@dept.in,12345,\n";
        mvc.perform(multipart(API + "/people/bulk").file(new MockMultipartFile("file", "people.csv", "text/csv", csv.getBytes()))
                        .session(admin).header("X-CSRF-TOKEN", csrf(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped[0].line").value(4)).andExpect(jsonPath("$.skipped[1].line").value(5))
                .andExpect(jsonPath("$.skipped[2].line").value(6));
        assertThat(users.findByUsername("gpf100").orElseThrow().isMustChangePassword()).isTrue();
        assertThat(users.findByUsername("typist1").orElseThrow().getDisplayName()).isEqualTo("Bala, K");
        // Passwords are never accepted through the browser.
        mvc.perform(multipart(API + "/people/bulk").file(new MockMultipartFile("file", "p.csv", "text/csv",
                        "username,name,email,password\nx1,X,x@dept.in,secret\n".getBytes()))
                        .session(admin).header("X-CSRF-TOKEN", csrf(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test void groupsAreManagedAndArchivedGroupsAreReadOnly() throws Exception {
        var admin = admin();
        String created = send(admin, API + "/groups", "{\"name\":\"Office\",\"memberIds\":[\"" + member + "\",\"" + other
                + "\"],\"adminIds\":[\"" + member + "\"],\"adminsOnly\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.group.memberCount").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(created, "$.group.id"));
        send(admin, API + "/groups", "{\"name\":\"X\",\"memberIds\":[\"" + member + "\",\"" + other + "\"],\"adminIds\":[]}")
                .andExpect(status().isBadRequest());
        send(admin, API + "/groups/" + id + "/members/" + member + "/role", "{\"role\":\"member\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAST_GROUP_ADMIN"));
        send(admin, API + "/groups/" + id + "/members/" + other + "/role", "{\"role\":\"admin\"}").andExpect(status().isOk());
        send(admin, API + "/groups/" + id + "/rename", "{\"name\":\"Main office\"}").andExpect(jsonPath("$.group.name").value("Main office"));
        read(admin, API + "/groups?q=main").andExpect(jsonPath("$.items[0].id").value(id.toString()));
        chat.send("member", id, UUID.randomUUID(), "Before archiving");
        send(admin, API + "/groups/" + id + "/archive", "{\"archived\":true}").andExpect(jsonPath("$.group.archived").value(true));
        assertThatThrownBy(() -> chat.send("member", id, UUID.randomUUID(), "After archiving"))
                .hasMessageContaining("archived");
        assertThat(chat.conversation("member", id).archived()).isTrue();
        assertThat(chat.history("member", id, null, null).messages()).hasSize(1);
        send(admin, API + "/groups/" + id + "/archive", "{\"archived\":false}").andExpect(status().isOk());
        chat.send("member", id, UUID.randomUUID(), "Restored");
        send(admin, API + "/groups/" + id + "/members/" + other + "/remove", "{}").andExpect(jsonPath("$.group.memberCount").value(1));
        UUID third = account("third", "third@dept.in", false, PASSWORD);
        send(admin, API + "/groups/" + id + "/members", "{\"userIds\":[\"" + third + "\"]}").andExpect(jsonPath("$.members.length()").value(2));
        read(admin, API + "/audit").andExpect(jsonPath("$[0].action").value("GROUP_MEMBERS_ADDED"))
                .andExpect(jsonPath("$[0].detail").value("Main office"));
    }

    @Test void theServerImportCreatesEmployeesWhoFinishTheirFirstSignInByEmail() throws Exception {
        String csv = "﻿GPF Number,Name,Email,Mobile,Cadre,Password\r\n"
                + "TN500,\"Officer \"\"One\"\"\",one@dept.in,9800000001,Superintendent,Start@Pass1\r\n"
                + "tn500,Same GPF,two@dept.in,,,x\r\n"
                + ",No Login,three@dept.in,,,x\r\n"
                + "\r\n";
        var report = importer.importCsv(new StringReader(csv.replace("GPF Number", "gpf_number")), true, null, 10);
        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.skipped()).extracting(EmployeeImport.Problem::line).containsExactly(3, 4);
        // A repeated GPF number is a repeated login ID; the reason names the earlier line, never the value.
        assertThat(report.skipped().getFirst().reason()).isEqualTo("Same login ID as line 2.");
        var account = users.findByUsername("tn500").orElseThrow();
        assertThat(account.getDisplayName()).isEqualTo("Officer \"One\"");
        assertThat(account.getGpfNumber()).isEqualTo("TN500");
        assertThat(encoder.matches("Start@Pass1", account.getPasswordHash())).isTrue();
        var session = new MockHttpSession();
        send(session, "/api/v1/users/login", "{\"username\":\"TN500\",\"password\":\"Start@Pass1\"}")
                .andExpect(jsonPath("$.status").value("PASSWORD_CHANGE_REQUIRED"));
        send(session, "/api/v1/users/login/verify", "{\"newPassword\":\"" + NEW
                + "\",\"confirmPassword\":\"" + NEW + "\"}").andExpect(status().isOk());
        assertThatThrownBy(() -> importer.importCsv(new StringReader("name,email\nA,a@dept.in\n"), true, null, 10))
                .hasMessageContaining("gpf_number");
    }

    @Test void bootstrapCreatesAnAdministratorWithAnEmailAndNeverChangesAnExistingAccount() {
        var repository = mock(UserRepository.class);
        when(repository.findByUsername("root")).thenReturn(Optional.empty());
        new AccountBootstrap(repository, encoder, " Root ", "BootstrapPassword!1", "Root@Dept.in").run(null);
        verify(repository).save(argThat(user -> user.getUsername().equals("root") && user.isAdministrator()
                && user.isMustChangePassword() && "root@dept.in".equals(user.getEmail())));
        var existing = mock(UserRepository.class);
        var account = new AppUser("root", "Root", "hash");
        when(existing.findByUsername("root")).thenReturn(Optional.of(account));
        when(existing.findByEmail("root@dept.in")).thenReturn(Optional.empty());
        new AccountBootstrap(existing, encoder, "root", "BootstrapPassword!1", "root@dept.in").run(null);
        verify(existing, never()).save(any());
        assertThat(account.getEmail()).isEqualTo("root@dept.in");
        assertThat(account.isAdministrator()).isFalse();
        assertThatThrownBy(() -> new AccountBootstrap(repository, encoder, "admin2", "BootstrapPassword!1", "").run(null))
                .hasMessageContaining("BOOTSTRAP_EMAIL");
        assertThat(new AdminService.Issued("x", "secret-value").toString()).doesNotContain("secret-value");
    }
}
