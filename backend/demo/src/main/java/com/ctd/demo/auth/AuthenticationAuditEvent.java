package com.ctd.demo.auth;

import java.time.Instant;

/** Records a credential-free authentication outcome for security auditing. */
public record AuthenticationAuditEvent(
        Instant occurredAt, String username, String clientAddress, Outcome outcome) {
    public enum Outcome { SUCCESS, FAILURE }
}
