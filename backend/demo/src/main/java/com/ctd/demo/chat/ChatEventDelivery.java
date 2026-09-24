package com.ctd.demo.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;

/** 2026-09-23: Publish only committed changes; a notification failure must not fail a saved send. */
@Component
public class ChatEventDelivery {
    public static final String CHANNEL = "messenger:chat:changed:v1";
    private static final Logger log = LoggerFactory.getLogger(ChatEventDelivery.class);
    private final ChatWebSocketHandler sockets;
    private final StringRedisTemplate redis;
    private final Environment environment;
    private final Executor executor;

    public ChatEventDelivery(ChatWebSocketHandler sockets, StringRedisTemplate redis, Environment environment,
            @Qualifier("chatEventsExecutor") Executor executor) {
        this.sockets = sockets;
        this.redis = redis;
        this.environment = environment;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(ChatChanged event) {
        try {
            executor.execute(() -> publish(event));
        } catch (RuntimeException exception) {
            log.warn("Chat live event queue unavailable; clients will reconcile over HTTP");
        }
    }

    private void publish(ChatChanged event) {
        for (String username : event.usernames()) {
            try {
                if (environment.matchesProfiles("local", "test")) sockets.changed(username);
                else redis.convertAndSend(CHANNEL, username);
            } catch (RuntimeException exception) {
                // Polling reconciles missed hints; do not log message bodies or participant identities.
                log.warn("Chat live notification unavailable; clients will reconcile over HTTP");
            }
        }
    }
}
