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
    @Autowired com.ctd.demo.chat.ChatService chat;
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
    void sharedFilesArePaginatedAndClearingPreservesOtherMembersDownloads() throws Exception {
        String id = direct();
        for (int i = 0; i < 52; i++) upload("alice", id, UUID.randomUUID(), "report-" + i + ".pdf", PDF).andExpect(status().isOk());
        String path = API + "/conversations/" + id;
        var first = mvc.perform(get(path + "/files").with(user("bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(50))
                .andExpect(jsonPath("$.hasMore").value(true)).andReturn();
        String json = first.getResponse().getContentAsString();
        Number before = JsonPath.read(json, "$.messages[49].sequence");
        String file = JsonPath.read(json, "$.messages[0].attachment.id");
        mvc.perform(get(path + "/files").param("before", before.toString()).with(user("bob")))
                .andExpect(jsonPath("$.messages.length()").value(2)).andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get(path + "/files").with(user("eve"))).andExpect(status().isNotFound());
        mvc.perform(get(path + "/files")).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/clear").with(user("alice")).with(csrf())).andExpect(status().isOk());
        mvc.perform(get(path + "/files").with(user("alice"))).andExpect(jsonPath("$.messages.length()").value(0));
        mvc.perform(get(API + "/files/" + file).with(user("alice"))).andExpect(status().isNotFound());
        mvc.perform(get(API + "/files/" + file).with(user("bob"))).andExpect(status().isOk());
        assertThat(storedFiles()).isEqualTo(52);
    }

    @Test
    void sharedGroupFilesRespectJoinHistoryAndHiddenMessages() throws Exception {
        UUID id = chat.createGroup("alice", "Files team", java.util.List.of(bob), false).id();
        upload("alice", id.toString(), UUID.randomUUID(), "earlier.pdf", PDF).andExpect(status().isOk());
        chat.addMembers("alice", id, java.util.List.of(eve));
        var uploaded = upload("bob", id.toString(), UUID.randomUUID(), "later.pdf", PDF).andExpect(status().isOk()).andReturn();
        String messageId = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.id");
        String path = API + "/conversations/" + id + "/files";
        mvc.perform(get(path).with(user("eve"))).andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].attachment.name").value("later.pdf"));
        chat.delete("eve", UUID.fromString(messageId), false);
        mvc.perform(get(path).with(user("eve"))).andExpect(jsonPath("$.messages.length()").value(0));
        mvc.perform(get(path).with(user("alice"))).andExpect(jsonPath("$.messages.length()").value(2));
    }

    @Test
    void forwardingCopiesTheFileAndUnsendingExpiresOnlyTheOriginal() throws Exception {
        String id = direct();
        var withEve = mvc.perform(post(API + "/conversations/direct").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + eve + "\"}")).andReturn();
        String eveId = JsonPath.read(withEve.getResponse().getContentAsString(), "$.id");
        var sent = upload("alice", id, UUID.randomUUID(), "report.pdf", PDF).andExpect(status().isOk()).andReturn();
        String messageId = JsonPath.read(sent.getResponse().getContentAsString(), "$.id");
        var forwarded = mvc.perform(post(API + "/messages/" + messageId + "/forward").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + eveId + "\",\"clientId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.forwarded").value(true))
                .andExpect(jsonPath("$.text").value("See attached"))
                .andExpect(jsonPath("$.attachment.name").value("report.pdf")).andReturn();
        String copyId = JsonPath.read(forwarded.getResponse().getContentAsString(), "$.attachment.id");
        assertThat(storedFiles()).isEqualTo(2);
        // Bob can forward only into his own conversations.
        mvc.perform(post(API + "/messages/" + messageId + "/forward").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"" + eveId + "\",\"clientId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(API + "/messages/" + messageId + "/delete").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"everyone\":true}")).andExpect(status().isOk());
        mvc.perform(get(API + "/conversations/" + id + "/messages").with(user("bob")))
                .andExpect(jsonPath("$.messages[0].deleted").value(true))
                .andExpect(jsonPath("$.messages[0].text").value(""))
                .andExpect(jsonPath("$.messages[0].attachment").doesNotExist());
        // The unsent file's bytes are gone straight away, not at the next hourly retention run.
        assertThat(storedFiles()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM chat_files WHERE deleted_at IS NOT NULL", Integer.class))
                .isEqualTo(1);
        assertThat(retention.purgeExpired(Instant.now().plusSeconds(1))).isZero();
        mvc.perform(get(API + "/files/" + copyId).with(user("eve"))).andExpect(status().isOk());
    }
    @Test
    void filesSentTogetherFormOneMessageThatForwardsAndUnsendsAsAWhole() throws Exception {
        String id = direct();
        UUID clientId = UUID.randomUUID();
        var request = multipart(API + "/conversations/" + id + "/files")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF))
                .file(new MockMultipartFile("file", "b.txt", "text/plain", "notes".getBytes()))
                .file(new MockMultipartFile("file", "c.csv", "text/csv", "x,y".getBytes()))
                .param("clientId", clientId.toString()).param("text", "Three files");
        var sent = mvc.perform(request.with(user("alice")).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Three files"))
                .andExpect(jsonPath("$.attachment.name").value("a.pdf"))
                .andExpect(jsonPath("$.attachments.length()").value(3))
                .andExpect(jsonPath("$.attachments[1].name").value("b.txt"))
                .andExpect(jsonPath("$.attachments[2].name").value("c.csv")).andReturn();
        String json = sent.getResponse().getContentAsString();
        String messageId = JsonPath.read(json, "$.id");
        String second = JsonPath.read(json, "$.attachments[1].id");
        // A retry of the same files returns the same message and leaves no extra bytes behind.
        mvc.perform(request.with(user("alice")).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(messageId));
        assertThat(storedFiles()).isEqualTo(3);
        mvc.perform(get(API + "/conversations/" + id + "/messages").with(user("bob")))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].attachments.length()").value(3));
        mvc.perform(get(API + "/files/" + second).with(user("bob"))).andExpect(status().isOk());
        mvc.perform(get(API + "/files/" + second).with(user("eve"))).andExpect(status().isNotFound());
        var withEve = mvc.perform(post(API + "/conversations/direct").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + eve + "\"}")).andReturn();
        String eveId = JsonPath.read(withEve.getResponse().getContentAsString(), "$.id");
        mvc.perform(post(API + "/messages/" + messageId + "/forward").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + eveId + "\",\"clientId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attachments.length()").value(3));
        assertThat(storedFiles()).isEqualTo(6);
        mvc.perform(post(API + "/messages/" + messageId + "/delete").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"everyone\":true}")).andExpect(status().isOk());
        assertThat(storedFiles()).isEqualTo(3);
        mvc.perform(get(API + "/files/" + second).with(user("bob"))).andExpect(status().isGone());
    }
    @Test
    void moreThanFiveFilesInOneMessageAreRejected() throws Exception {
        String id = direct();
        var request = multipart(API + "/conversations/" + id + "/files").param("clientId", UUID.randomUUID().toString());
        for (int i = 0; i < 6; i++) request.file(new MockMultipartFile("file", i + ".txt", "text/plain", "x".getBytes()));
        mvc.perform(request.with(user("alice")).with(csrf())).andExpect(status().isBadRequest());
        assertThat(storedFiles()).isZero();
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
    void oneUndeletableFileDoesNotStopRetentionForTheRest() throws Exception {
        String id = direct();
        upload("alice", id, UUID.randomUUID(), "stuck.pdf", PDF).andExpect(status().isOk());
        upload("alice", id, UUID.randomUUID(), "fine.pdf", PDF).andExpect(status().isOk());
        String stuckKey = db.queryForObject("SELECT storage_key FROM chat_files WHERE original_name='stuck.pdf'", String.class);
        // A non-empty directory where the file should be: the volume refuses to delete it.
        Path stuck = properties.root().resolve(stuckKey);
        Files.delete(stuck);
        Files.createDirectories(stuck.resolve("blocker"));
        Instant later = Instant.now().plus(properties.retention()).plusSeconds(1);
        assertThat(retention.purgeExpired(later)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM chat_files WHERE original_name='fine.pdf' AND deleted_at IS NOT NULL",
                Integer.class)).isEqualTo(1);
        // The stuck file waits for a later run instead of being retried first forever.
        assertThat(db.queryForObject("SELECT deleted_at IS NULL AND purge_retry_at IS NOT NULL FROM chat_files "
                + "WHERE original_name='stuck.pdf'", Boolean.class)).isTrue();
        assertThat(retention.purgeExpired(later)).isZero();
        // Once the volume allows it and the retry time has come, it is removed.
        Files.delete(stuck.resolve("blocker"));
        db.update("UPDATE chat_files SET purge_retry_at=? WHERE original_name='stuck.pdf'", java.sql.Timestamp.from(later.minusSeconds(1)));
        assertThat(retention.purgeExpired(later)).isEqualTo(1);
        assertThat(Files.exists(stuck)).isFalse();
    }

    @Test
    void uploadsAreRefusedBeforeStorageWhenTheSenderMayNotPostOrIsOverQuota() throws Exception {
        UUID announcements = chat.createGroup("alice", "News", java.util.List.of(bob), true).id();
        // A non-admin in an announcement group is refused before any bytes reach the volume.
        upload("bob", announcements.toString(), UUID.randomUUID(), "notes.pdf", PDF).andExpect(status().isForbidden());
        assertThat(storedFiles()).isZero();

        String id = direct();
        String messageId = JsonPath.read(upload("alice", id, UUID.randomUUID(), "shared.pdf", PDF)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        // Bob has already uploaded his daily quota: new uploads and forwarded copies are refused.
        db.update("INSERT INTO chat_files(id,conversation_id,uploader_id,original_name,content_type,size_bytes,sha256,"
                        + "storage_key,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), UUID.fromString(id), bob,
                "earlier.zip", "application/zip", properties.dailyQuota().toBytes(), "0".repeat(64), "quota/" + UUID.randomUUID(),
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        upload("bob", id, UUID.randomUUID(), "more.pdf", PDF).andExpect(status().isTooManyRequests());
        mvc.perform(post(API + "/messages/" + messageId + "/forward").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"" + id + "\",\"clientId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isTooManyRequests());
        assertThat(storedFiles()).isEqualTo(1);
        // Uploads older than a day no longer count.
        db.update("UPDATE chat_files SET created_at=? WHERE original_name='earlier.zip'",
                java.sql.Timestamp.from(Instant.now().minusSeconds(86_401)));
        upload("bob", id, UUID.randomUUID(), "more.pdf", PDF).andExpect(status().isOk());
    }

    @Test
    void downloadsAreCachedPrivatelyAndRevalidatedAfterTheAccessCheck() throws Exception {
        String id = direct();
        String fileId = JsonPath.read(upload("alice", id, UUID.randomUUID(), "photo.pdf", PDF)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.attachment.id");
        String etag = mvc.perform(get(API + "/files/" + fileId).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=86400, private"))
                .andReturn().getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        mvc.perform(get(API + "/files/" + fileId).header("If-None-Match", etag).with(user("bob")))
                .andExpect(status().isNotModified()).andExpect(content().bytes(new byte[0]));
        // Revalidation still checks access: a non-member with the ETag gets nothing.
        mvc.perform(get(API + "/files/" + fileId).header("If-None-Match", etag).with(user("eve")))
                .andExpect(status().isNotFound());
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
