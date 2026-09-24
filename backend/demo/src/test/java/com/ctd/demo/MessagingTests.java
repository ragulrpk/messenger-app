package com.ctd.demo;

import com.ctd.demo.chat.ChatService;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessagingTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired ChatService chat;
    UUID alice, bob, eve;
    private static final String API = "/api/v1/chat";

    // 2026-09-23: HTTP test identities carry the same credential binding as a real login.
    private org.springframework.test.web.servlet.request.RequestPostProcessor user(String name) {
        return request -> {
            var processed = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(name)
                    .postProcessRequest(request);
            processed.getSession().setAttribute(com.ctd.demo.auth.CredentialStamp.ATTRIBUTE,
                    com.ctd.demo.auth.CredentialStamp.of(users.findByUsername(name).orElseThrow().getPasswordHash()));
            return processed;
        };
    }

    @AfterEach
    void clean() {
        db.update("DELETE FROM chat_messages");
        db.update("DELETE FROM chat_members");
        db.update("DELETE FROM chat_conversations");
    }
    @BeforeEach
    void seed() {
        clean();
        users.deleteAll();
        alice = users.save(new AppUser("alice", "Alice", "unused-test-hash")).getId();
        bob = users.save(new AppUser("bob", "Bob", "unused-test-hash")).getId();
        eve = users.save(new AppUser("eve", "Eve", "unused-test-hash")).getId();
    }
    private String direct(String name, UUID target) throws Exception {
        var result = mvc.perform(post(API + "/conversations/direct").with(user(name)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + target + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
    private String body(UUID client, String text) { return "{\"clientId\":\"" + client + "\",\"text\":\"" + text + "\"}"; }

    @Test
    void onlyMembersCanReadOrSendAndDirectoryDoesNotExposeSecrets() throws Exception {
        String id = direct("alice", bob);
        mvc.perform(get(API + "/users?q=BO").with(user("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(bob.toString()))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
        mvc.perform(get(API + "/users?q=alice").with(user("alice"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(API + "/users?q=%").with(user("alice"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(API + "/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/conversations/" + id + "/messages").with(user("eve"))).andExpect(status().isNotFound());
        mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("eve")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "intrusion"))).andExpect(status().isNotFound());
        mvc.perform(get(API + "/conversations").with(user("eve"))).andExpect(jsonPath("$.conversations.length()").value(0));
        mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "no csrf"))).andExpect(status().isForbidden());
        mvc.perform(get(API + "/conversations/" + UUID.randomUUID() + "/messages").with(user("eve"))).andExpect(status().isNotFound());
    }
    @Test
    void sendPersistsForBothUsersAndRetriesReturnOneMessage() throws Exception {
        String id = direct("alice", bob);
        assertThat(direct("bob", alice)).isEqualTo(id);
        UUID client = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("alice")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body(client, "Hello Bob")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.senderId").value(alice.toString()));
        }
        // A separate request/principal restores history from the database.
        mvc.perform(get(API + "/conversations/" + id + "/messages").with(user("bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].text").value("Hello Bob"));
        mvc.perform(get(API + "/conversations").with(user("bob")))
                .andExpect(jsonPath("$.conversations[0].name").value("Alice"))
                .andExpect(jsonPath("$.conversations[0].lastMessage.text").value("Hello Bob"));
        mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(client, "Changed text"))).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT count(*) FROM chat_messages", Integer.class)).isEqualTo(1);
    }
    @Test
    void paginationHasNoGapsAndNewMessagesAreAvailableAfterCursor() throws Exception {
        UUID id = UUID.fromString(direct("alice", bob));
        for (int i = 0; i < 55; i++) chat.send("alice", id, UUID.randomUUID(), "Message " + i);
        var latest = chat.history("bob", id, null, null);
        assertThat(latest.messages()).hasSize(50);
        assertThat(latest.hasMore()).isTrue();
        assertThat(latest.messages().getFirst().text()).isEqualTo("Message 5");
        var older = chat.history("bob", id, latest.messages().getFirst().sequence(), null);
        assertThat(older.messages()).hasSize(5);
        assertThat(older.hasMore()).isFalse();
        assertThat(older.messages().getFirst().text()).isEqualTo("Message 0");
        chat.send("bob", id, UUID.randomUUID(), "Reply");
        var incoming = chat.history("alice", id, null, latest.messages().getLast().sequence());
        assertThat(incoming.messages()).hasSize(1);
        assertThat(incoming.messages().getFirst().text()).isEqualTo("Reply");
        var catchup = chat.history("bob", id, null, 0L);
        assertThat(catchup.messages()).hasSize(50);
        assertThat(catchup.hasMore()).isTrue();
        assertThat(chat.history("bob", id, null, catchup.messages().getLast().sequence()).messages()).hasSize(6);
    }
    @Test
    void conversationCursorReturnsStableNonOverlappingPages() {
        for (int i = 0; i < 52; i++) {
            var person = users.save(new AppUser("person" + i, "Person " + i, "unused-test-hash"));
            chat.direct("alice", person.getId());
        }
        var first = chat.conversations("alice", null);
        var second = chat.conversations("alice", first.nextCursor());
        assertThat(first.conversations()).hasSize(50);
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.conversations()).hasSize(2);
        assertThat(second.nextCursor()).isNull();
        var firstIds = new HashSet<>(first.conversations().stream().map(ChatService.Conversation::id).toList());
        assertThat(second.conversations()).extracting(ChatService.Conversation::id)
                .noneMatch(firstIds::contains);
    }
    @Test
    void rejectsInvalidMessagesCursorsAndDisabledSessions() throws Exception {
        String id = direct("alice", bob);
        for (String text : new String[] { " ", "x".repeat(4001) })
            mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("alice")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), text))).andExpect(status().isBadRequest());
        mvc.perform(get(API + "/conversations/invalid/messages").with(user("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get(API + "/conversations/" + id + "/messages?before=1&after=1").with(user("alice"))).andExpect(status().isBadRequest());
        mvc.perform(post(API + "/conversations/direct").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + alice + "\"}")).andExpect(status().isBadRequest());
        db.update("UPDATE app_users SET enabled=false WHERE id=?", alice);
        mvc.perform(get(API + "/conversations/" + id + "/messages").with(user("alice"))).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/users?q=alice").with(user("bob"))).andExpect(jsonPath("$.length()").value(0));
    }
    @Test
    void concurrentCreationAndRetriesDoNotDuplicateData() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> chat.direct("alice", bob));
            var second = pool.submit(() -> chat.direct("bob", alice));
            UUID id = first.get().id();
            assertThat(second.get().id()).isEqualTo(id);
            UUID client = UUID.randomUUID();
            var send1 = pool.submit(() -> chat.send("alice", id, client, "Concurrent retry"));
            var send2 = pool.submit(() -> chat.send("alice", id, client, "Concurrent retry"));
            assertThat(send1.get().id()).isEqualTo(send2.get().id());
            assertThat(db.queryForObject("SELECT count(*) FROM chat_messages", Integer.class)).isEqualTo(1);
        }
    }
}
