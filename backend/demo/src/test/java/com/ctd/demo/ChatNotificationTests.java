package com.ctd.demo;

import com.ctd.demo.chat.*;
import com.ctd.demo.user.*;
import com.ctd.demo.auth.CredentialStamp;
import java.util.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatNotificationTests {
    @Autowired ChatService chat;
    @Autowired ChatNotifications notifications;
    @Autowired ChatPush push;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired org.springframework.security.core.session.SessionRegistry registry;
    @Autowired MockMvc mvc;
    @MockitoBean WebPushTransport transport;
    UUID alice,bob,eve;
    @BeforeEach void seed() {
        clean(); users.deleteAll();
        alice=users.save(new AppUser("alice","Alice","test-hash")).getId();
        bob=users.save(new AppUser("bob","Bob","test-hash")).getId();
        eve=users.save(new AppUser("eve","Eve","test-hash")).getId();
    }
    @AfterEach void clean() {
        db.update("DELETE FROM chat_push_subscriptions"); db.update("DELETE FROM chat_messages"); db.update("DELETE FROM chat_files");
        db.update("DELETE FROM chat_members"); db.update("DELETE FROM chat_conversations"); db.update("DELETE FROM user_presence");
    }
    @Test void individualArrivalsMentionsAndRetryAreIndependentOfTheLatestPreview() {
        UUID id=chat.createGroup("alice","Team",List.of(bob),false).id();
        long cursor=notifications.feed("bob",null).cursor();
        chat.preferences("bob",id,null,true,null,null);
        UUID client=UUID.randomUUID();
        var mentioned=chat.send("alice",id,client,"Hi @bob, check this");
        chat.send("alice",id,client,"Hi @bob, check this");
        chat.send("alice",id,UUID.randomUUID(),"An ordinary message after the mention");
        var page=notifications.feed("bob",cursor);
        assertThat(page.notifications()).hasSize(1);
        assertThat(page.notifications().getFirst().messageId()).isEqualTo(mentioned.id());
        assertThat(page.notifications().getFirst().mentioned()).isTrue();
        assertThat(notifications.feed("bob",page.cursor()).notifications()).isEmpty();
        assertThat(notifications.feed("alice",cursor).notifications()).isEmpty();
        assertThat(notifications.feed("eve",cursor).notifications()).isEmpty();
    }
    @Test void paginationRecoversEveryMessageAndAccessIsRechecked() {
        UUID id=chat.direct("alice",bob).id();
        long cursor=notifications.feed("bob",null).cursor();
        for(int i=0;i<55;i++) chat.send("alice",id,UUID.randomUUID(),"Arrival " + i);
        var first=notifications.feed("bob",cursor);
        assertThat(first.notifications()).hasSize(50); assertThat(first.hasMore()).isTrue();
        var second=notifications.feed("bob",first.cursor()); assertThat(second.notifications()).hasSize(5);
        chat.clear("bob",id); assertThat(notifications.feed("bob",cursor).notifications()).isEmpty();
        var next=chat.send("alice",id,UUID.randomUUID(),"After clear");
        assertThat(notifications.feed("bob",second.cursor()).notifications()).hasSize(1);
        chat.delete("alice",next.id(),true); assertThat(notifications.feed("bob",second.cursor()).notifications()).isEmpty();
    }
    @Test void groupRemovalAndHiddenMessagesCannotNotify() {
        UUID id=chat.createGroup("alice","Team",List.of(bob),false).id();
        long cursor=notifications.feed("bob",null).cursor();
        var message=chat.send("alice",id,UUID.randomUUID(),"Hidden");
        chat.delete("bob",message.id(),false); assertThat(notifications.feed("bob",cursor).notifications()).isEmpty();
        chat.send("alice",id,UUID.randomUUID(),"Removed"); chat.removeMember("alice",id,bob);
        assertThat(notifications.feed("bob",cursor).notifications()).isEmpty();
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor asBob() {
        return request -> { var result=user("bob").postProcessRequest(request); result.getSession().setAttribute(CredentialStamp.ATTRIBUTE,CredentialStamp.of("test-hash")); return result; };
    }
    private void subscribeBob(MockHttpSession session) throws Exception {
        when(transport.enabled()).thenReturn(true); when(transport.publicKey()).thenReturn("public");
        var generator=KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
        var publicKey=(ECPublicKey)generator.generateKeyPair().getPublic();
        byte[] raw=new byte[65]; raw[0]=4;
        var coords=List.of(publicKey.getW().getAffineX(),publicKey.getW().getAffineY());
        for(int i=0;i<2;i++){ byte[] value=coords.get(i).toByteArray();int n=Math.min(32,value.length);System.arraycopy(value,value.length-n,raw,1+i*32+32-n,n); }
        String key=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String body="{\"endpoint\":\"https://fcm.googleapis.com/test-device\",\"keys\":{\"p256dh\":\""+key+"\",\"auth\":\"AAAAAAAAAAAAAAAAAAAAAA\"}}";
        mvc.perform(post("/api/v1/chat/push/subscribe").session(session).with(asBob()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        // Signing in registers the session; push goes only to devices whose session is still alive.
        registry.registerNewSession(session.getId(), "bob");
    }
    @Test void pushStopsWhenTheSubscribingSessionEnds() throws Exception {
        var session=new MockHttpSession();
        subscribeBob(session);
        UUID id=chat.direct("alice",bob).id(); chat.send("alice",id,UUID.randomUUID(),"After sign-out");
        // Signed in elsewhere, signed out or idle for five minutes: the session is gone.
        registry.removeSessionInformation(session.getId());
        push.deliver();
        verify(transport,never()).send(anyString(),anyString(),anyString(),any());
        assertThat(db.queryForObject("SELECT count(*) FROM chat_push_subscriptions",Integer.class)).isZero();
    }
    @Test void pushSendsOutsideTransactionsAndLeavesIdleDevicesAlone() throws Exception {
        subscribeBob(new MockHttpSession());
        Timestamp idle=db.queryForObject("SELECT retry_at FROM chat_push_subscriptions",Timestamp.class);
        push.deliver();
        // Nothing to send: the device is neither contacted nor rewritten.
        verify(transport,never()).send(anyString(),anyString(),anyString(),any());
        assertThat(db.queryForObject("SELECT retry_at FROM chat_push_subscriptions",Timestamp.class)).isEqualTo(idle);
        UUID id=chat.direct("alice",bob).id(); chat.send("alice",id,UUID.randomUUID(),"Background message");
        when(transport.send(anyString(),anyString(),anyString(),any())).thenAnswer(call -> {
            // The network call holds no transaction, and the lease keeps other replicas away meanwhile.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(db.queryForObject("SELECT retry_at FROM chat_push_subscriptions",Timestamp.class).toInstant()).isAfter(Instant.now());
            return 201;
        });
        push.deliver();
        verify(transport,times(1)).send(anyString(),anyString(),anyString(),any());
        assertThat(db.queryForObject("SELECT cursor_position FROM chat_push_subscriptions",Long.class)).isEqualTo(notifications.position());
        assertThat(db.queryForObject("SELECT failures FROM chat_push_subscriptions",Integer.class)).isZero();
    }
    @Test void pushRetriesAndLogoutRevokesTheDevice() throws Exception {
        var session=new MockHttpSession();
        subscribeBob(session);
        UUID id=chat.direct("alice",bob).id(); chat.send("alice",id,UUID.randomUUID(),"Background message");
        when(transport.send(anyString(),anyString(),anyString(),any())).thenReturn(503,201);
        push.deliver();
        assertThat(db.queryForObject("SELECT failures FROM chat_push_subscriptions",Integer.class)).isEqualTo(1);
        db.update("UPDATE chat_push_subscriptions SET retry_at=?",Timestamp.from(Instant.now().minusSeconds(1)));
        push.deliver();
        assertThat(db.queryForObject("SELECT cursor_position FROM chat_push_subscriptions",Long.class)).isEqualTo(notifications.position());
        mvc.perform(post("/api/v1/users/logout").session(session).with(asBob()).with(csrf())).andExpect(status().isNoContent());
        assertThat(db.queryForObject("SELECT count(*) FROM chat_push_subscriptions",Integer.class)).isZero();
    }
}
