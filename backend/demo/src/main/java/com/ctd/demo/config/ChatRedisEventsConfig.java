package com.ctd.demo.config;

import com.ctd.demo.chat.ChatEventDelivery;
import com.ctd.demo.chat.ChatWebSocketHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 2026-09-23: Fan out committed hints to sockets on every production backend instance. */
@Configuration
@Profile("!local & !test")
public class ChatRedisEventsConfig {
    @Bean
    RedisMessageListenerContainer chatEventListener(RedisConnectionFactory factory, ChatWebSocketHandler sockets,
            @Qualifier("chatEventsExecutor") Executor executor) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.setTaskExecutor(executor);
        container.addMessageListener((message, pattern) ->
                sockets.changed(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(ChatEventDelivery.CHANNEL));
        return container;
    }
}
