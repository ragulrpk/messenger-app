package com.ctd.demo;

import com.ctd.demo.chat.*;
import com.ctd.demo.user.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class MessageConcurrencyTests {
    @Autowired ChatService chat;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean ChatRepository chats;
    UUID alice,bob;
    @BeforeEach void seed() {
        clean(); users.deleteAll();
        alice=users.save(new AppUser("alice","Alice","test-hash")).getId();
        bob=users.save(new AppUser("bob","Bob","test-hash")).getId();
    }
    @AfterEach void clean() {
        db.update("DELETE FROM chat_push_subscriptions"); db.update("DELETE FROM chat_messages"); db.update("DELETE FROM chat_files");
        db.update("DELETE FROM chat_members"); db.update("DELETE FROM chat_conversations"); db.update("DELETE FROM user_presence");
    }
    /** The contender has completed its initial lookup before the winner changes state and commits.
     *  Latches, rather than sleeps, make the stale-read ordering deterministic. */
    private Throwable compete(UUID conversation, Runnable contender, Runnable winner) throws Exception {
        var thread=new AtomicReference<Thread>();
        var waiting=new CountDownLatch(1);
        doAnswer(call -> {
            if(Thread.currentThread()==thread.get()) waiting.countDown();
            return call.callRealMethod();
        }).when(chats).lockConversation(conversation);
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<?> future=transactions.execute(status -> {
                chats.lockConversation(conversation);
                var pending=executor.submit(() -> { thread.set(Thread.currentThread()); contender.run(); });
                try { assertThat(waiting.await(3,TimeUnit.SECONDS)).as("contender reached the locked conversation after its first lookup").isTrue(); }
                catch(InterruptedException exception){ Thread.currentThread().interrupt();throw new IllegalStateException(exception); }
                winner.run();
                return pending;
            });
            try { future.get(5,TimeUnit.SECONDS); return null; }
            catch(ExecutionException exception){ return exception.getCause(); }
        }
    }
    private void rejected(Throwable failure,int status) {
        assertThat(failure).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException)failure).getStatusCode().value()).isEqualTo(status);
    }
    @Test void anEditWaitingBehindAnUnsendCannotRestoreDeletedText() throws Exception {
        UUID id=chat.direct("alice",bob).id(); var message=chat.send("alice",id,UUID.randomUUID(),"Original");
        rejected(compete(id,()->chat.edit("alice",message.id(),"Restored"),()->chat.delete("alice",message.id(),true)),410);
        assertThat(db.queryForObject("SELECT body FROM chat_messages WHERE id=?",String.class,message.id())).isEmpty();
    }
    @Test void aReactionWaitingBehindAnUnsendCannotRecreateReactions() throws Exception {
        UUID id=chat.direct("alice",bob).id(); var message=chat.send("alice",id,UUID.randomUUID(),"Original");
        rejected(compete(id,()->chat.react("bob",message.id(),"👍"),()->chat.delete("alice",message.id(),true)),410);
        assertThat(db.queryForObject("SELECT count(*) FROM chat_reactions",Integer.class)).isZero();
    }
    @Test void aQueuedEditRechecksTheCurrentBodyInsteadOfSkippingAValidEdit() throws Exception {
        UUID id=chat.direct("alice",bob).id(); var message=chat.send("alice",id,UUID.randomUUID(),"Original");
        assertThat(compete(id,()->chat.edit("alice",message.id(),"Original"),()->chat.edit("alice",message.id(),"Intervening edit"))).isNull();
        assertThat(db.queryForObject("SELECT body FROM chat_messages WHERE id=?",String.class,message.id())).isEqualTo("Original");
    }
    @Test void aQueuedMutationLosesAccessWhenItsMemberIsRemoved() throws Exception {
        UUID id=chat.createGroup("alice","Team",List.of(bob),false).id(); var message=chat.send("bob",id,UUID.randomUUID(),"Original");
        rejected(compete(id,()->chat.edit("bob",message.id(),"Unauthorized"),()->chat.removeMember("alice",id,bob)),404);
    }
    @Test void aQueuedReactionLosesAccessAfterTheCallerClearsHistory() throws Exception {
        UUID id=chat.direct("alice",bob).id(); var message=chat.send("alice",id,UUID.randomUUID(),"Original");
        rejected(compete(id,()->chat.react("bob",message.id(),"👍"),()->chat.clear("bob",id)),404);
    }
    @Test void duplicateConcurrentHidesReturnNotFoundInsteadOfADatabaseError() throws Exception {
        UUID id=chat.direct("alice",bob).id(); var message=chat.send("alice",id,UUID.randomUUID(),"Original");
        rejected(compete(id,()->chat.delete("bob",message.id(),false),()->chat.delete("bob",message.id(),false)),404);
        assertThat(db.queryForObject("SELECT count(*) FROM chat_message_hidden",Integer.class)).isEqualTo(1);
    }
    @Test void aSendWaitingBehindMemberRemovalIsRejected() throws Exception {
        UUID id=chat.createGroup("alice","Team",List.of(bob),false).id();
        rejected(compete(id,()->chat.send("bob",id,UUID.randomUUID(),"Unauthorized"),()->chat.removeMember("alice",id,bob)),404);
        assertThat(db.queryForObject("SELECT count(*) FROM chat_messages",Integer.class)).isZero();
    }
    @Test void aReplyWaitingBehindAClearCannotQuoteClearedHistory() throws Exception {
        UUID id=chat.direct("alice",bob).id(); var message=chat.send("alice",id,UUID.randomUUID(),"Original");
        rejected(compete(id,()->chat.send("bob",id,UUID.randomUUID(),"Reply",message.id()),()->chat.clear("bob",id)),400);
    }
}
