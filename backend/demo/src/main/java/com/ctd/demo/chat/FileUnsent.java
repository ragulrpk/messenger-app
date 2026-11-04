package com.ctd.demo.chat;

import java.util.UUID;

/** A file whose message was deleted for everyone; its bytes are removed once that delete commits. */
public record FileUnsent(UUID fileId) { }
