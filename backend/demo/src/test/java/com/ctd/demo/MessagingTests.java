package com.ctd.demo;

import com.ctd.demo.chat.ChatService;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.event.RecordApplicationEvents
class MessagingTests {
    @Autowired org.springframework.test.context.event.ApplicationEvents events;
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
        db.update("DELETE FROM user_presence");
    }
    @BeforeEach
    void seed() {
        clean();
        users.deleteAll();
        alice = users.save(new AppUser("alice", "Alice", "unused-test-hash")).getId();
        bob = users.save(new AppUser("bob", "Bob", "unused-test-hash")).getId();
        eve = users.save(new AppUser("eve", "Eve", "unused-test-hash")).getId();
    }
    @Test
    void previewSkipsMessagesDeletedForTheViewerOnly() {
        UUID id = chat.direct("alice", bob).id();
        var first = chat.send("alice", id, UUID.randomUUID(), "First");
        var second = chat.send("alice", id, UUID.randomUUID(), "Second");
        chat.delete("bob", second.id(), false);
        // Bob sees the newest message still shown to him; Alice, who did not delete it, still sees it.
        assertThat(chat.conversation("bob", id).lastMessage().id()).isEqualTo(first.id());
        assertThat(chat.conversations("bob", null).conversations().getFirst().lastMessage().id()).isEqualTo(first.id());
        assertThat(chat.conversation("alice", id).lastMessage().id()).isEqualTo(second.id());
        chat.delete("bob", first.id(), false);
        assertThat(chat.conversation("bob", id).lastMessage()).isNull();
        // A newer message is previewed again as usual.
        var third = chat.send("alice", id, UUID.randomUUID(), "Third");
        assertThat(chat.conversation("bob", id).lastMessage().id()).isEqualTo(third.id());
    }

    private String direct(String name, UUID target) throws Exception {
        var result = mvc.perform(post(API + "/conversations/direct").with(user(name)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + target + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
    private String body(UUID client, String text) { return "{\"clientId\":\"" + client + "\",\"text\":\"" + text + "\"}"; }

    @Test
    void largeMixedChangeBacklogsContinueWithoutSkippingEditsDeletesReactionsOrHides() {
        UUID id=chat.direct("alice",bob).id();
        var messages=new java.util.ArrayList<ChatService.Message>();
        for(int i=0;i<805;i++) messages.add(chat.send("alice",id,UUID.randomUUID(),"Original " + i));
        long cursor=chat.history("bob",id,null,null).revision();
        var expected=new HashSet<UUID>();
        // Change order deliberately differs from message sequence order.
        for(int i=messages.size()-1;i>=0;i--) {
            var message=messages.get(i); expected.add(message.id());
            if(i%8==0) chat.delete("bob",message.id(),false);
            else if(i%3==0) chat.edit("alice",message.id(),"Edited " + i);
            else if(i%3==1) chat.react("bob",message.id(),"👍");
            else chat.delete("alice",message.id(),true);
        }
        var found=new HashSet<UUID>();
        var first=chat.history("bob",id,null,messages.getLast().sequence(),cursor);
        assertThat(first.hasMore()).isFalse();
        assertThat(first.hasMoreChanges()).isTrue();
        assertThat(first.revision()).isEqualTo(cursor+500);
        first.changed().forEach(message->found.add(message.id())); found.addAll(first.removed());
        // An already visited message changes again while the client is between pages.
        var changedAgain=messages.get(802);
        chat.edit("alice",changedAgain.id(),"Changed during catch-up");
        var next=chat.history("bob",id,null,messages.getLast().sequence(),first.revision());
        assertThat(next.hasMoreChanges()).isFalse();
        assertThat(next.changed()).anySatisfy(message->{
            assertThat(message.id()).isEqualTo(changedAgain.id()); assertThat(message.text()).isEqualTo("Changed during catch-up");
        });
        next.changed().forEach(message->found.add(message.id())); found.addAll(next.removed());
        assertThat(found).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(chat.history("bob",id,null,messages.getLast().sequence(),next.revision()).changed()).isEmpty();
    }

    @Test
    void clearingDirectAndGroupHistoryAffectsOnlyTheCallerAndAllowsNewMessages() throws Exception {
        for (UUID id : List.of(UUID.fromString(direct("alice", bob)), chat.createGroup("alice", "Clear team", List.of(bob), false).id())) {
            var old = chat.send("bob", id, UUID.randomUUID(), "old history");
            String path = API + "/conversations/" + id;
            mvc.perform(post(path + "/clear").with(user("eve")).with(csrf())).andExpect(status().isNotFound());
            mvc.perform(post(path + "/clear").with(user("alice"))).andExpect(status().isForbidden());
            mvc.perform(post(path + "/clear").with(user("alice")).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.historyFromSequence").value(old.sequence()))
                    .andExpect(jsonPath("$.lastMessage").isEmpty()).andExpect(jsonPath("$.unread").value(0));
            mvc.perform(get(path + "/messages").with(user("alice")))
                    .andExpect(jsonPath("$.messages.length()").value(0));
            mvc.perform(get(path + "/messages/search").param("q", "history").with(user("alice")))
                    .andExpect(jsonPath("$.messages.length()").value(0));
            mvc.perform(get(path + "/messages").with(user("bob")))
                    .andExpect(jsonPath("$.messages.length()").value(1));
            chat.send("bob", id, UUID.randomUUID(), "new message");
            mvc.perform(get(path + "/messages").with(user("alice")))
                    .andExpect(jsonPath("$.messages.length()").value(1))
                    .andExpect(jsonPath("$.messages[0].text").value("new message"));
        }
    }

    @Test
    void messageSearchIsScopedLiteralAndPaginatedAcrossOlderHistory() throws Exception {
        UUID id = UUID.fromString(direct("alice", bob));
        UUID other = UUID.fromString(direct("alice", eve));
        for (int i = 0; i < 55; i++) chat.send("alice", id, UUID.randomUUID(), "Find 100%_ item " + i);
        chat.send("alice", id, UUID.randomUUID(), "Find 100xx is not a literal match");
        chat.send("alice", other, UUID.randomUUID(), "Find 100%_ elsewhere");
        var hidden = chat.send("alice", id, UUID.randomUUID(), "Find 100%_ hidden");
        chat.delete("bob", hidden.id(), false);
        var deleted = chat.send("alice", id, UUID.randomUUID(), "Find 100%_ deleted");
        chat.delete("alice", deleted.id(), true);
        var edited = chat.send("alice", id, UUID.randomUUID(), "Find 100%_ old text");
        chat.edit("alice", edited.id(), "Changed text");
        String path = API + "/conversations/" + id + "/messages/search";
        var first = mvc.perform(get(path).param("q", "  FIND 100%_  ").with(user("bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(50))
                .andExpect(jsonPath("$.hasMore").value(true)).andReturn();
        Number before = JsonPath.read(first.getResponse().getContentAsString(), "$.messages[49].sequence");
        mvc.perform(get(path).param("q", "find 100%_").param("before", before.toString()).with(user("bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(5))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get(path).param("q", "find").with(user("eve"))).andExpect(status().isNotFound());
        mvc.perform(get(path).param("q", "find")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).param("q", " ").with(user("bob")))
                .andExpect(jsonPath("$.messages.length()").value(0));
        mvc.perform(get(path).param("q", "find").param("before", "0").with(user("bob")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void groupMessageSearchRespectsJoinHistoryAndRemoval() throws Exception {
        UUID id = chat.createGroup("alice", "Search team", List.of(bob), false).id();
        chat.send("alice", id, UUID.randomUUID(), "secret before joining");
        chat.addMembers("alice", id, List.of(eve));
        chat.send("bob", id, UUID.randomUUID(), "Visible after joining");
        String path = API + "/conversations/" + id + "/messages/search";
        mvc.perform(get(path).param("q", "joining").with(user("eve")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].text").value("Visible after joining"));
        chat.removeMember("alice", id, eve);
        mvc.perform(get(path).param("q", "joining").with(user("eve"))).andExpect(status().isNotFound());
    }

    @Test
    void groupSearchIncludesOlderAndHiddenGroupsButExcludesNonMembers() throws Exception {
        UUID wanted = chat.createGroup("alice", "Old 100%_ Team", List.of(bob), false).id();
        chat.preferences("alice", wanted, null, null, true, null);
        for (int i = 0; i < 51; i++) chat.createGroup("alice", "Recent " + i, List.of(bob), false);
        chat.createGroup("bob", "Private 100%_ Team", List.of(eve), false);
        chat.createGroup("alice", "Not a literal 100xx Team", List.of(bob), false);
        mvc.perform(get(API + "/conversations/search").param("q", " 100%_ TEAM ").with(user("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(wanted.toString()))
                .andExpect(jsonPath("$[0].hidden").value(true));
        mvc.perform(get(API + "/conversations/search").param("q", "100%_").with(user("eve")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Private 100%_ Team"));
        mvc.perform(get(API + "/conversations/search").param("q", " ").with(user("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        chat.leave("alice", wanted);
        mvc.perform(get(API + "/conversations/search").param("q", "Old").with(user("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

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
    void preferencesAndReadStateArePerMemberAndSurviveReload() throws Exception {
        UUID id = UUID.fromString(direct("alice", bob));
        chat.send("alice", id, UUID.randomUUID(), "One");
        chat.send("alice", id, UUID.randomUUID(), "Two");
        mvc.perform(get(API + "/conversations").with(user("bob")))
                .andExpect(jsonPath("$.conversations[0].unread").value(2))
                .andExpect(jsonPath("$.conversations[0].pinned").value(false));
        mvc.perform(get(API + "/conversations").with(user("alice")))
                .andExpect(jsonPath("$.conversations[0].unread").value(0));
        mvc.perform(post(API + "/conversations/" + id + "/preferences").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true,\"muted\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pinned").value(true))
                .andExpect(jsonPath("$.muted").value(true));
        mvc.perform(get(API + "/conversations").with(user("alice")))
                .andExpect(jsonPath("$.conversations[0].pinned").value(false))
                .andExpect(jsonPath("$.conversations[0].muted").value(false));
        mvc.perform(post(API + "/conversations/" + id + "/preferences").with(user("eve")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}")).andExpect(status().isNotFound());
        long first = chat.history("bob", id, null, null).messages().getFirst().sequence();
        mvc.perform(post(API + "/conversations/" + id + "/read").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":" + first + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(1));
        mvc.perform(post(API + "/conversations/" + id + "/read").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":0}"))
                .andExpect(jsonPath("$.unread").value(1));
        mvc.perform(post(API + "/conversations/" + id + "/read").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(jsonPath("$.unread").value(0));
        mvc.perform(post(API + "/conversations/" + id + "/preferences").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"unread\":true}")).andExpect(jsonPath("$.unread").value(1));
        mvc.perform(post(API + "/conversations/" + id + "/read").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(jsonPath("$.unread").value(0));
        mvc.perform(post(API + "/conversations/" + id + "/preferences").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(jsonPath("$.hidden").value(true)).andExpect(jsonPath("$.pinned").value(false));
        chat.send("alice", id, UUID.randomUUID(), "Back again");
        mvc.perform(get(API + "/conversations").with(user("bob")))
                .andExpect(jsonPath("$.conversations[0].hidden").value(false))
                .andExpect(jsonPath("$.conversations[0].unread").value(1));
        chat.send("bob", id, UUID.randomUUID(), "Reply");
        mvc.perform(get(API + "/conversations").with(user("bob")))
                .andExpect(jsonPath("$.conversations[0].unread").value(0));
    }
    @Test
    void senderSeesMessagesAsSentThenDeliveredThenSeen() throws Exception {
        UUID id = UUID.fromString(direct("alice", bob));
        long first = chat.send("alice", id, UUID.randomUUID(), "One").sequence();
        long second = chat.send("alice", id, UUID.randomUUID(), "Two").sequence();
        mvc.perform(get(API + "/conversations").with(user("alice")))
                .andExpect(jsonPath("$.conversations[0].targetDeliveredSequence").value(0))
                .andExpect(jsonPath("$.conversations[0].targetReadSequence").value(0));
        // Bob's list polls are read-only; his app reporting the arrivals delivers them without marking them seen.
        mvc.perform(get(API + "/conversations").with(user("bob"))).andExpect(status().isOk());
        mvc.perform(get(API + "/conversations").with(user("alice")))
                .andExpect(jsonPath("$.conversations[0].targetDeliveredSequence").value(0));
        mvc.perform(post(API + "/delivered").with(user("bob")).with(csrf())).andExpect(status().isOk());
        mvc.perform(get(API + "/conversations").with(user("alice")))
                .andExpect(jsonPath("$.conversations[0].targetDeliveredSequence").value(second))
                .andExpect(jsonPath("$.conversations[0].targetReadSequence").value(0));
        mvc.perform(post(API + "/conversations/" + id + "/read").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":" + first + "}"))
                .andExpect(status().isOk());
        mvc.perform(get(API + "/conversations").with(user("alice")))
                .andExpect(jsonPath("$.conversations[0].targetReadSequence").value(first));
        // Sending advances the sender's own positions, and reading never moves backward.
        mvc.perform(post(API + "/conversations/" + id + "/read").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sequence\":0}"));
        mvc.perform(get(API + "/conversations").with(user("bob")))
                .andExpect(jsonPath("$.conversations[0].targetDeliveredSequence").value(second))
                .andExpect(jsonPath("$.conversations[0].targetReadSequence").value(second));
        mvc.perform(get(API + "/conversations").with(user("alice")))
                .andExpect(jsonPath("$.conversations[0].targetReadSequence").value(first));
    }
    private org.springframework.test.web.servlet.ResultActions action(String name, UUID message, String verb, String json)
            throws Exception {
        return mvc.perform(post(API + "/messages/" + message + "/" + verb).with(user(name)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
    @Test
    void membersReplyEditReactForwardAndDeleteMessages() throws Exception {
        UUID id = UUID.fromString(direct("alice", bob));
        UUID withEve = UUID.fromString(direct("alice", eve));
        var original = chat.send("alice", id, UUID.randomUUID(), "Hello");
        var elsewhere = chat.send("alice", withEve, UUID.randomUUID(), "Elsewhere");
        var loaded = chat.history("bob", id, null, null);
        long revision = loaded.revision();
        mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"clientId\":\"" + UUID.randomUUID()
                        + "\",\"text\":\"Hi\",\"replyToId\":\"" + original.id() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replyTo.text").value("Hello"))
                .andExpect(jsonPath("$.replyTo.senderName").value("Alice"));
        mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"clientId\":\"" + UUID.randomUUID()
                        + "\",\"text\":\"Hi\",\"replyToId\":\"" + elsewhere.id() + "\"}"))
                .andExpect(status().isBadRequest());

        action("bob", original.id(), "edit", "{\"text\":\"Hijacked\"}").andExpect(status().isForbidden());
        action("alice", original.id(), "edit", "{\"text\":\" \"}").andExpect(status().isBadRequest());
        action("alice", original.id(), "edit", "{\"text\":\"Hello again\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.edited").value(true))
                .andExpect(jsonPath("$.text").value("Hello again"));
        action("bob", original.id(), "reaction", "{\"emoji\":\"🐍\"}").andExpect(status().isBadRequest());
        action("bob", original.id(), "reaction", "{\"emoji\":\"👍\"}")
                .andExpect(jsonPath("$.reactions[0].emoji").value("👍"))
                .andExpect(jsonPath("$.reactions[0].count").value(1)).andExpect(jsonPath("$.reactions[0].mine").value(true));
        action("eve", original.id(), "reaction", "{\"emoji\":\"👍\"}").andExpect(status().isNotFound());

        // Bob's open history fetches the edit and the reaction since the revision it last saw.
        var changes = chat.history("bob", id, null, loaded.messages().getLast().sequence(), revision);
        assertThat(changes.revision()).isGreaterThan(revision);
        assertThat(changes.changed()).singleElement().satisfies(m -> {
            assertThat(m.text()).isEqualTo("Hello again");
            assertThat(m.reactions()).singleElement().satisfies(r -> assertThat(r.mine()).isTrue());
        });
        mvc.perform(get(API + "/conversations/" + id + "/messages").with(user("alice")))
                .andExpect(jsonPath("$.messages[0].reactions[0].mine").value(false));

        action("alice", original.id(), "forward", "{\"conversationId\":\"" + withEve + "\",\"clientId\":\""
                + UUID.randomUUID() + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.forwarded").value(true)).andExpect(jsonPath("$.text").value("Hello again"));
        action("bob", original.id(), "forward", "{\"conversationId\":\"" + withEve + "\",\"clientId\":\""
                + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());

        action("bob", original.id(), "delete", "{\"everyone\":true}").andExpect(status().isForbidden());
        action("bob", original.id(), "delete", "{\"everyone\":false}").andExpect(status().isOk());
        assertThat(chat.history("bob", id, null, null).messages()).extracting(ChatService.Message::id)
                .doesNotContain(original.id());
        assertThat(chat.history("bob", id, null, null, changes.revision()).removed()).containsExactly(original.id());
        assertThat(chat.history("alice", id, null, null).messages()).extracting(ChatService.Message::id)
                .contains(original.id());

        action("alice", original.id(), "delete", "{\"everyone\":true}").andExpect(status().isOk());
        mvc.perform(get(API + "/conversations/" + id + "/messages").with(user("alice")))
                .andExpect(jsonPath("$.messages[0].deleted").value(true))
                .andExpect(jsonPath("$.messages[0].text").value(""))
                .andExpect(jsonPath("$.messages[0].reactions.length()").value(0))
                .andExpect(jsonPath("$.messages[1].replyTo.deleted").value(true))
                .andExpect(jsonPath("$.messages[1].replyTo.text").value(""));
        action("alice", original.id(), "edit", "{\"text\":\"Back\"}").andExpect(status().isGone());
    }
    private org.springframework.test.web.servlet.ResultActions groupCall(String name, String path, String json)
            throws Exception {
        return mvc.perform(post(API + "/conversations/" + path).with(user(name)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
    @Test
    void adminsManageGroupsAndMembersLoseAccessWhenTheyLeave() throws Exception {
        var created = groupCall("alice", "group", "{\"name\":\" Team \",\"memberIds\":[\"" + bob + "\",\"" + eve + "\"]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.name").value("Team")).andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$.memberCount").value(3)).andReturn();
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
        groupCall("alice", "group", "{\"name\":\"Solo\",\"memberIds\":[\"" + alice + "\"]}").andExpect(status().isBadRequest());
        mvc.perform(get(API + "/conversations").with(user("bob")))
                .andExpect(jsonPath("$.conversations[0].name").value("Team"))
                .andExpect(jsonPath("$.conversations[0].role").value("member"))
                .andExpect(jsonPath("$.conversations[0].targetStatus").doesNotExist());

        // Group receipts: seen only once every other member has read.
        long sequence = chat.send("alice", id, UUID.randomUUID(), "Hello team").sequence();
        chat.read("bob", id, null);
        assertThat(chat.conversations("alice", null).conversations().getFirst().targetReadSequence()).isZero();
        chat.read("eve", id, null);
        assertThat(chat.conversations("alice", null).conversations().getFirst().targetReadSequence()).isEqualTo(sequence);

        groupCall("bob", id + "/rename", "{\"name\":\"Mine\"}").andExpect(status().isForbidden());
        groupCall("bob", id + "/members/" + eve + "/remove", "{}").andExpect(status().isForbidden());
        groupCall("alice", id + "/rename", "{\"name\":\"Core team\"}").andExpect(jsonPath("$.name").value("Core team"));

        groupCall("alice", id + "/members/" + eve + "/remove", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(API + "/conversations").with(user("eve"))).andExpect(jsonPath("$.conversations.length()").value(0));
        mvc.perform(get(API + "/conversations/" + id + "/messages").with(user("eve"))).andExpect(status().isNotFound());
        groupCall("alice", id + "/members", "{\"userIds\":[\"" + eve + "\"]}").andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get(API + "/conversations").with(user("eve")))
                .andExpect(jsonPath("$.conversations[0].unread").value(0));

        groupCall("alice", id + "/members/" + alice + "/role", "{\"role\":\"member\"}").andExpect(status().isBadRequest());
        groupCall("alice", id + "/members/" + bob + "/role", "{\"role\":\"admin\"}").andExpect(status().isOk());
        groupCall("alice", id + "/members/" + alice + "/role", "{\"role\":\"member\"}").andExpect(status().isOk());
        groupCall("bob", id + "/leave", "{}").andExpect(status().isOk());
        // The last admin left, so the longest-standing remaining member is promoted.
        mvc.perform(get(API + "/conversations/" + id + "/members").with(user("alice")))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].role").value("admin"))
                .andExpect(jsonPath("$[1].role").value("member"));

        String directId = direct("alice", bob);
        groupCall("alice", directId + "/rename", "{\"name\":\"Nope\"}").andExpect(status().isBadRequest());
        groupCall("alice", directId + "/leave", "{}").andExpect(status().isBadRequest());
    }
    /** Everyone who received a refresh hint since the events were last cleared. */
    private java.util.List<String> hinted() {
        return events.stream(com.ctd.demo.chat.ChatChanged.class).flatMap(e -> e.usernames().stream()).sorted().toList();
    }
    @Test
    void deliveryAndReadsSignalOnlyTheSendersNotTheWholeGroup() throws Exception {
        var created = groupCall("alice", "group", "{\"name\":\"Big\",\"memberIds\":[\"" + bob + "\",\"" + eve + "\"]}")
                .andReturn();
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
        chat.send("alice", id, UUID.randomUUID(), "Hello");
        events.clear();
        chat.conversations("bob", null);
        assertThat(hinted()).as("listing alone delivers nothing").isEmpty();
        chat.delivered("bob");
        assertThat(hinted()).as("delivery reaches only the sender").containsExactly("alice");
        events.clear();
        chat.delivered("bob");
        assertThat(hinted()).as("nothing new, no hints").isEmpty();
        chat.read("bob", id, null);
        assertThat(hinted()).as("a read reaches the reader's tabs and the sender").containsExactly("alice", "bob");
        events.clear();
        chat.read("bob", id, null);
        assertThat(hinted()).as("re-reading only refreshes the reader's own tabs").containsExactly("bob");
        // A new message still reaches every member.
        events.clear();
        chat.send("eve", id, UUID.randomUUID(), "Hi all");
        assertThat(hinted()).containsExactly("alice", "bob", "eve");
        assertThat(chat.conversations("alice", null).conversations().getFirst().memberCount()).isEqualTo(3);
        groupCall("alice", id + "/members/" + eve + "/remove", "{}");
        assertThat(chat.conversations("alice", null).conversations().getFirst().memberCount()).isEqualTo(2);
    }
    @Test
    void addedMembersSeeOnlyMessagesFromWhenTheyJoined() throws Exception {
        var created = groupCall("alice", "group", "{\"name\":\"Board\",\"memberIds\":[\"" + bob + "\"]}").andReturn();
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
        var before = chat.send("alice", id, UUID.randomUUID(), "Confidential");
        groupCall("alice", id + "/members", "{\"userIds\":[\"" + eve + "\"]}").andExpect(status().isOk());
        assertThat(chat.history("eve", id, null, null).messages()).isEmpty();
        mvc.perform(get(API + "/conversations").with(user("eve")))
                .andExpect(jsonPath("$.conversations[0].lastMessage").doesNotExist());
        action("eve", before.id(), "reaction", "{\"emoji\":\"👍\"}").andExpect(status().isNotFound());
        mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("eve")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"clientId\":\"" + UUID.randomUUID()
                        + "\",\"text\":\"Hi\",\"replyToId\":\"" + before.id() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"clientId\":\"" + UUID.randomUUID()
                        + "\",\"text\":\"Welcome\",\"replyToId\":\"" + before.id() + "\"}"))
                .andExpect(status().isOk());
        var seen = chat.history("eve", id, null, null).messages();
        assertThat(seen).singleElement().satisfies(m -> {
            assertThat(m.text()).isEqualTo("Welcome");
            assertThat(m.replyTo().text()).isEqualTo("Message from before you joined");
        });
        assertThat(chat.history("bob", id, null, null).messages().getLast().replyTo().text()).isEqualTo("Confidential");
        // Removed and added back: what was said in between stays hidden.
        groupCall("alice", id + "/members/" + eve + "/remove", "{}");
        chat.send("alice", id, UUID.randomUUID(), "While eve was away");
        groupCall("alice", id + "/members", "{\"userIds\":[\"" + eve + "\"]}");
        assertThat(chat.history("eve", id, null, null).messages()).isEmpty();
    }
    @Test
    void lookupFindsPastedUsernamesAndSkipsUnknownOnes() throws Exception {
        mvc.perform(post(API + "/users/lookup").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernames\":[\"BOB\",\" @eve \",\"nobody\",\"alice\",\"bob\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value("bob")).andExpect(jsonPath("$[1].username").value("eve"));
    }
    @Test
    void mentionsPointAtOneUniquePersonEvenWithSharedNamesRenamesAndLeaving() throws Exception {
        UUID first = users.save(new AppUser("indhuja.r", "Indhuja", "unused-test-hash")).getId();
        UUID second = users.save(new AppUser("indhuja.k", "Indhuja", "unused-test-hash")).getId();
        var created = groupCall("alice", "group",
                "{\"name\":\"Team\",\"memberIds\":[\"" + first + "\",\"" + second + "\"]}").andReturn();
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
        var sent = chat.send("alice", id, UUID.randomUUID(), "Please review, @indhuja.k. Mail alice@tn.gov.in for access.");
        assertThat(sent.mentions()).extracting(ChatService.Mention::id).containsExactly(second);
        db.update("UPDATE app_users SET display_name='Indhuja K' WHERE id=?", second);
        groupCall("alice", id + "/members/" + second + "/remove", "{}");
        var shown = chat.history("indhuja.r", id, null, null).messages().getFirst().mentions();
        assertThat(shown).singleElement().satisfies(m -> {
            assertThat(m.username()).isEqualTo("indhuja.k");
            assertThat(m.name()).as("a rename shows the current name").isEqualTo("Indhuja K");
        });
    }
    @Test
    void groupsHoldAtMostOneHundredMembers() {
        var people = new java.util.ArrayList<UUID>();
        for (int i = 0; i < 100; i++) people.add(users.save(new AppUser("member" + i, "Member " + i, "unused-test-hash")).getId());
        assertThatThrownBy(() -> chat.createGroup("alice", "Too big", people, false))
                .hasMessageContaining("Choose between 1 and 99 other people");
        var full = chat.createGroup("alice", "Full", people.subList(0, 99), false);
        assertThat(full.memberCount()).isEqualTo(100);
        assertThatThrownBy(() -> chat.addMembers("alice", full.id(), List.of(bob)))
                .hasMessageContaining("at most 100 members");
    }
    @Test
    void onlyAdminsPostInAnnouncementGroups() throws Exception {
        var created = groupCall("alice", "group",
                "{\"name\":\"News\",\"memberIds\":[\"" + bob + "\"],\"adminsOnly\":true}")
                .andExpect(jsonPath("$.adminsOnly").value(true)).andReturn();
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
        var notice = chat.send("alice", id, UUID.randomUUID(), "Office closed Friday");
        mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "Thanks")))
                .andExpect(status().isForbidden());
        // Members can still react to announcements.
        action("bob", notice.id(), "reaction", "{\"emoji\":\"👍\"}").andExpect(status().isOk());
        groupCall("bob", id + "/settings", "{\"adminsOnly\":false}").andExpect(status().isForbidden());
        groupCall("alice", id + "/settings", "{\"adminsOnly\":false}").andExpect(jsonPath("$.adminsOnly").value(false));
        mvc.perform(post(API + "/conversations/" + id + "/messages").with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "Thanks")))
                .andExpect(status().isOk());
    }
    @Test
    void presenceIsSavedAndVisibleToContactsOnly() throws Exception {
        direct("alice", bob);
        mvc.perform(get(API + "/presence").with(user("alice")))
                .andExpect(jsonPath("$.status").value("online")).andExpect(jsonPath("$.note").value(""));
        mvc.perform(post(API + "/presence").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"meeting\",\"note\":\"  Back at 3  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.note").value("Back at 3"));
        mvc.perform(get(API + "/presence").with(user("alice"))).andExpect(jsonPath("$.status").value("meeting"));
        mvc.perform(get(API + "/conversations").with(user("bob")))
                .andExpect(jsonPath("$.conversations[0].targetStatus").value("meeting"))
                .andExpect(jsonPath("$.conversations[0].targetNote").value("Back at 3"));
        for (String bad : new String[] { "{\"status\":\"away\",\"note\":\"\"}",
                "{\"status\":\"busy\",\"note\":\"" + "x".repeat(161) + "\"}", "{\"status\":\"busy\"}" })
            mvc.perform(post(API + "/presence").with(user("alice")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        mvc.perform(post(API + "/presence").with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"busy\",\"note\":\"\"}"))
                .andExpect(status().isForbidden());
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
