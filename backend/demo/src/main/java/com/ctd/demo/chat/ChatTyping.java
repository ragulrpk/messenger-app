package com.ctd.demo.chat;

import java.util.List;
import java.util.UUID;

/** Ephemeral participant-only activity; never contains draft text. */
public record ChatTyping(List<String> usernames, UUID conversationId, boolean active) {
    public ChatTyping { usernames = List.copyOf(usernames); }
}
