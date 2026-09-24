package com.ctd.demo.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/** Supports account-wide session revocation after account or credential changes. */
@Service
public class SessionRevocationService {
    private final SessionRegistry sessions;

    public SessionRevocationService(SessionRegistry sessions) { this.sessions = sessions; }

    public void revokeAll(String username) {
        // 2026-09-23: Redis supports principal lookup, not enumeration; sockets observe expiration too.
        if (sessions instanceof SpringSessionBackedSessionRegistry<?>) {
            sessions.getAllSessions(username, false).forEach(session -> session.expireNow());
            return;
        }
        sessions.getAllPrincipals().stream()
                .filter(principal -> username.equals(principalName(principal)))
                .flatMap(principal -> sessions.getAllSessions(principal, false).stream())
                .forEach(session -> session.expireNow());
    }

    private String principalName(Object principal) {
        return principal instanceof UserDetails details ? details.getUsername() : String.valueOf(principal);
    }
}
