package com.ctd.demo.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Writes authentication audit events without passwords or request bodies. */
@Component
public class AuthenticationAuditLogger {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationAuditLogger.class);

    @EventListener
    public void record(AuthenticationAuditEvent event) {
        log.info("Authentication audit outcome={} username={} clientAddress={} occurredAt={}",
                event.outcome(), event.username(), event.clientAddress(), event.occurredAt());
    }
}
