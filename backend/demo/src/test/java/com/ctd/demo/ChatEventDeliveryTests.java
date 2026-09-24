package com.ctd.demo;

import com.ctd.demo.chat.*;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/** 2026-09-23: Production routing and transport failure must not change a committed send result. */
class ChatEventDeliveryTests {
    @Test
    void productionPublishesParticipantNamesToRedisInsteadOfOnlyLocalSockets() {
        var sockets = mock(ChatWebSocketHandler.class);
        var redis = mock(StringRedisTemplate.class);
        var delivery = new ChatEventDelivery(sockets, redis, new MockEnvironment(), Runnable::run);
        delivery.committed(new ChatChanged(List.of("alice", "bob")));
        verify(redis).convertAndSend(ChatEventDelivery.CHANNEL, "alice");
        verify(redis).convertAndSend(ChatEventDelivery.CHANNEL, "bob");
        verifyNoInteractions(sockets);
    }

    @Test
    void redisOutageOrFullQueueDoesNotTurnCommittedSendIntoFailure() {
        var sockets = mock(ChatWebSocketHandler.class);
        var redis = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("offline")).when(redis).convertAndSend(anyString(), anyString());
        var event = new ChatChanged(List.of("alice"));
        assertThatCode(() -> new ChatEventDelivery(sockets, redis, new MockEnvironment(), Runnable::run)
                .committed(event)).doesNotThrowAnyException();
        assertThatCode(() -> new ChatEventDelivery(sockets, redis, new MockEnvironment(), command -> {
            throw new RejectedExecutionException();
        }).committed(event)).doesNotThrowAnyException();
    }
}
