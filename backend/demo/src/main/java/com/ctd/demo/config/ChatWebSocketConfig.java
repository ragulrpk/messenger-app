package com.ctd.demo.config;

import com.ctd.demo.chat.ChatWebSocketHandler;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** 2026-09-23: Cookie-authenticated, exact-origin WebSocket upgrade; no credentials in URLs. */
@Configuration
@EnableWebSocket
@EnableScheduling
public class ChatWebSocketConfig implements WebSocketConfigurer {
    private final ChatWebSocketHandler handler;
    private final List<String> origins;

    /** Keep slow sockets/Redis off the HTTP send thread; bounded overload falls back to polling. */
    @Bean
    static ThreadPoolTaskExecutor chatEventsExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("chat-events-");
        return executor;
    }

    public ChatWebSocketConfig(ChatWebSocketHandler handler, @Value("${app.cors.allowed-origins}") String origins) {
        this.handler = handler;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).toList();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/chat/events")
                .setAllowedOrigins(origins.toArray(String[]::new))
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                            WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        // Require Origin even for same-origin requests to prevent cross-site cookie hijacking.
                        if (!origins.contains(request.getHeaders().getOrigin())
                                || request.getPrincipal() == null
                                || !(request instanceof ServletServerHttpRequest servlet)) {
                            response.setStatusCode(HttpStatus.FORBIDDEN);
                            return false;
                        }
                        var session = servlet.getServletRequest().getSession(false);
                        if (session == null) return false;
                        attributes.put(ChatWebSocketHandler.HTTP_SESSION, session);
                        return true;
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                            WebSocketHandler wsHandler, Exception exception) { }
                });
    }
}
