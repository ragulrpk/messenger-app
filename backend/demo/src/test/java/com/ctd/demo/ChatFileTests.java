package com.ctd.demo;

import com.ctd.demo.chat.ChatFileProperties;
import com.ctd.demo.chat.ChatFileRetention;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatFileTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired ChatFileRetention retention;
    @Autowired ChatFileProperties properties;
    UUID alice, bob, eve;
    private static final String API = "/api/v1/chat";
    private static final byte[] PDF = "%PDF-1.7\nquarterly report".getBytes(StandardCharsets.US_ASCII);

    // 2026-09-23: HTTP test identities carry the same credential binding as a real login.
    private RequestPostProcessor user(String name) {
        return request -> {
            var processed = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(name)
                    .postProcessRequest(request);
            processed.getSession().setAttribute(com.ctd.demo.auth.CredentialStamp.ATTRIBUTE,
                    com.ctd.demo.auth.CredentialStamp.of(users.findByUsername(name).orElseThrow().getPasswordHash()));
            return processed;
        };
    }

    @AfterEach
    void clean() throws IOException {
        db.update("DELETE FROM chat_messages");
        db.update("DELETE FROM chat_files");
        db.update("DELETE FROM chat_members");
        db.update("DELETE FROM chat_conversations");
        try (Stream<Path> files = Files.walk(properties.root())) {
            for (Path file : files.filter(Files::isRegularFile).toList()) Files.delete(file);
        }
    }
    @BeforeEach
    void seed() throws IOException {
        clean();
        users.deleteAll();
        alice = users.save(new AppUser("alice", "Alice", "unused-test-hash")).getId();
        bob = users.save(new AppUser("bob", "Bob", "unused-test-hash")).getId();
        eve = users.save(new AppUser("eve", "Eve", "unused-test-hash")).getId();
    }
    private String direct() throws Exception {
        var result = mvc.perform(post(API + "/conversations/direct").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + bob + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
    private ResultActions upload(String name, String conversation, UUID clientId, String filename, byte[] bytes) throws Exception {
        return mvc.perform(multipart(API + "/conversations/" + conversation + "/files")
                .file(new MockMultipartFile("file", filename, "application/octet-stream", bytes))
                .param("clientId", clientId.toString()).param("text", "See attached")
                .with(user(name)).with(csrf()));
    }
    private long storedFiles() throws IOException {
        try (Stream<Path> files = Files.walk(properties.root())) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void membersShareAndDownloadFilesButOthersCannot() throws Exception {
        String id = direct();
        var result = upload("alice", id, UUID.randomUUID(), "../../Q3 report.pdf", PDF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("See attached"))
                .andExpect(jsonPath("$.attachment.name").value("Q3 report.pdf"))
                .andExpect(jsonPath("$.attachment.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.attachment.size").value(PDF.length))
                .andExpect(jsonPath("$.attachment.available").value(true))
                .andReturn();
        String fileId = JsonPath.read(result.getResponse().getContentAsString(), "$.attachment.id");
        assertThat(storedFiles()).isEqualTo(1);

        mvc.perform(get(API + "/conversations/" + id + "/messages").with(user("bob")))
                .andExpect(jsonPath("$.messages[0].attachment.id").value(fileId));
        mvc.perform(get(API + "/conversations").with(user("bob")))
                .andExpect(jsonPath("$.conversations[0].lastMessage.attachment.name").value("Q3 report.pdf"));
        mvc.perform(get(API + "/files/" + fileId).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(PDF))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mvc.perform(get(API + "/files/" + fileId).with(user("eve"))).andExpect(status().isNotFound());
        mvc.perform(get(API + "/files/" + UUID.randomUUID()).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get(API + "/files/" + fileId)).andExpect(status().isUnauthorized());
        upload("eve", id, UUID.randomUUID(), "intrusion.pdf", PDF).andExpect(status().isNotFound());
        assertThat(storedFiles()).isEqualTo(1);
    }

    @Test
    void retriesReturnOneMessageAndConflictingRetriesLeaveNoBytes() throws Exception {
        String id = direct();
        UUID client = UUID.randomUUID();
        String first = JsonPath.read(upload("alice", id, client, "notes.txt", "v1".getBytes())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        String retry = JsonPath.read(upload("alice", id, client, "notes.txt", "v1".getBytes())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(retry).isEqualTo(first);
        upload("alice", id, client, "notes.txt", "v2".getBytes()).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT count(*) FROM chat_messages", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM chat_files", Integer.class)).isEqualTo(1);
        assertThat(storedFiles()).isEqualTo(1);
    }

    @Test
    void rejectsUnsafeOrOversizedUploadsWithoutStoringThem() throws Exception {
        String id = direct();
        upload("alice", id, UUID.randomUUID(), "setup.exe", "MZ".getBytes())
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        upload("alice", id, UUID.randomUUID(), "photo.png", "<html><script>alert(1)</script>".getBytes())
                .andExpect(status().isUnsupportedMediaType());
        upload("alice", id, UUID.randomUUID(), "big.txt", new byte[1025])
                .andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        upload("alice", id, UUID.randomUUID(), "empty.txt", new byte[0]).andExpect(status().isBadRequest());
        upload("alice", id, UUID.randomUUID(), "noextension", "data".getBytes()).andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart(API + "/conversations/" + id + "/files").param("clientId", UUID.randomUUID().toString())
                .with(user("alice")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart(API + "/conversations/" + id + "/files")
                .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                .param("clientId", UUID.randomUUID().toString()).param("text", "x".repeat(4001))
                .with(user("alice")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart(API + "/conversations/" + id + "/files")
                .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                .param("clientId", UUID.randomUUID().toString()).with(user("alice")))
                .andExpect(status().isForbidden());
        assertThat(storedFiles()).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM chat_messages", Integer.class)).isZero();
    }

    @Test
    void expiredFilesAreRemovedFromDiskButTheirMessagesRemain() throws Exception {
        String id = direct();
        String fileId = JsonPath.read(upload("alice", id, UUID.randomUUID(), "old.pdf", PDF)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.attachment.id");
        assertThat(retention.purgeExpired(Instant.now())).isZero();
        assertThat(storedFiles()).isEqualTo(1);

        assertThat(retention.purgeExpired(Instant.now().plus(properties.retention()).plusSeconds(1))).isEqualTo(1);
        assertThat(storedFiles()).isZero();
        assertThat(retention.purgeExpired(Instant.now().plus(properties.retention()).plusSeconds(1))).isZero();
        // The retention job and the read path agree once the expiry time has actually passed.
        db.update("UPDATE chat_files SET expires_at=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        mvc.perform(get(API + "/files/" + fileId).with(user("bob")))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("FILE_EXPIRED"));
        mvc.perform(get(API + "/conversations/" + id + "/messages").with(user("bob")))
                .andExpect(jsonPath("$.messages[0].text").value("See attached"))
                .andExpect(jsonPath("$.messages[0].attachment.name").value("old.pdf"))
                .andExpect(jsonPath("$.messages[0].attachment.available").value(false));
    }

    @Test
    void fileOnlyMessagesNeedNoCaption() throws Exception {
        String id = direct();
        mvc.perform(multipart(API + "/conversations/" + id + "/files")
                        .file(new MockMultipartFile("file", "diagram.gif", "image/gif", "GIF89a....".getBytes()))
                        .param("clientId", UUID.randomUUID().toString()).with(user("alice")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(""))
                .andExpect(jsonPath("$.attachment.contentType").value("image/gif"));
    }
}
