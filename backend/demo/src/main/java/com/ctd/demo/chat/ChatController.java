package com.ctd.demo.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

/** Routes authenticated directory, conversation, and message requests to chat services. */
@RestController
@RequestMapping("/api/v1/chat")
@Validated
public class ChatController {
    private final ChatService chat;
    /** Receives the service that owns chat data and access checks. */
    public ChatController(ChatService chat) { this.chat = chat; }

    public record DirectRequest(@NotNull UUID userId) { }
    public record SendRequest(@NotNull UUID clientId, @NotBlank @Size(max = 4000) String text) { }

    /** Searches other enabled users in the directory. */
    @GetMapping("/users")
    public Object users(Authentication auth, @RequestParam(defaultValue = "") String q) {
        return chat.directory(auth.getName(), q);
    }
    /** Returns a page of the caller's conversations. */
    @GetMapping("/conversations")
    public Object conversations(Authentication auth,
            @RequestParam(required = false) @Size(max = 256) String cursor) {
        return chat.conversations(auth.getName(), cursor);
    }
    /** Opens or creates a direct conversation with the requested user. */
    @PostMapping("/conversations/direct")
    public Object direct(Authentication auth, @Valid @RequestBody DirectRequest request) {
        return chat.direct(auth.getName(), request.userId());
    }
    /** Returns a page of messages around the requested cursor. */
    @GetMapping("/conversations/{id}/messages")
    public Object messages(Authentication auth, @PathVariable UUID id,
            @RequestParam(required = false) Long before, @RequestParam(required = false) Long after) {
        return chat.history(auth.getName(), id, before, after);
    }
    /** Sends a validated message to a conversation. */
    @PostMapping("/conversations/{id}/messages")
    public Object send(Authentication auth, @PathVariable UUID id, @Valid @RequestBody SendRequest request) {
        return chat.send(auth.getName(), id, request.clientId(), request.text());
    }
}
