package com.ctd.demo.chat;

import java.util.List;

/** 2026-09-23: Participant-only invalidation; no message text or session secrets on the event bus. */
public record ChatChanged(List<String> usernames) {
    public ChatChanged { usernames = List.copyOf(usernames); }
}
