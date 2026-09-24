package com.ctd.demo;

import com.ctd.demo.chat.ChatWebSocketHandler;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.socket.*;
import static org.mockito.Mockito.*;

/** 2026-09-23: Expiry and connection limits without slow wall-clock waits. */
class ChatWebSocketHandlerTests {
    @Test
    @SuppressWarnings("unchecked")
    void expiredHttpSessionCannotOpenSocket() throws Exception {
        var registry = new SessionRegistryImpl();
        registry.registerNewSession("http", "alice");
        var users = mock(UserRepository.class);
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        var handler = new ChatWebSocketHandler(registry, users, mock(ObjectProvider.class), env);
        var http = mock(HttpSession.class);
        when(http.getId()).thenReturn("http");
        when(http.getMaxInactiveInterval()).thenReturn(1);
        when(http.getLastAccessedTime()).thenReturn(System.currentTimeMillis() - 5000);
        var socket = mock(WebSocketSession.class);
        when(socket.getPrincipal()).thenReturn(() -> "alice");
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        socket.getAttributes().put(ChatWebSocketHandler.HTTP_SESSION, http);
        handler.afterConnectionEstablished(socket);
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verify(socket, never()).sendMessage(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void boundsConnectionsAndReleasesSlotsWhenClosed() throws Exception {
        var registry = new SessionRegistryImpl();
        registry.registerNewSession("http", "alice");
        var users = mock(UserRepository.class);
        when(users.findByUsername("alice")).thenReturn(Optional.of(new AppUser("alice", "Alice", "unused")));
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        var handler = new ChatWebSocketHandler(registry, users, mock(ObjectProvider.class), env);
        var http = mock(HttpSession.class);
        when(http.getId()).thenReturn("http");
        var auth = UsernamePasswordAuthenticationToken.authenticated("alice", null, java.util.List.of());
        when(http.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .thenReturn(new SecurityContextImpl(auth));
        when(http.getAttribute(com.ctd.demo.auth.CredentialStamp.ATTRIBUTE))
                .thenReturn(com.ctd.demo.auth.CredentialStamp.of("unused"));
        var sockets = new WebSocketSession[9];
        for (int index = 0; index < sockets.length; index++) {
            var socket = mock(WebSocketSession.class);
            sockets[index] = socket;
            when(socket.getId()).thenReturn("socket-" + index);
            when(socket.getPrincipal()).thenReturn(auth);
            when(socket.isOpen()).thenReturn(true);
            when(socket.getAttributes()).thenReturn(new HashMap<>());
            socket.getAttributes().put(ChatWebSocketHandler.HTTP_SESSION, http);
            handler.afterConnectionEstablished(socket);
        }
        verify(sockets[8]).close(new CloseStatus(1008, "Too many connections"));
        verify(sockets[8], never()).sendMessage(any());
        handler.afterConnectionClosed(sockets[0], CloseStatus.NORMAL);
        handler.afterConnectionEstablished(sockets[8]);
        verify(sockets[8]).sendMessage(any(TextMessage.class));
        handler.shutdown();
    }
}
