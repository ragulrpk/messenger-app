package com.ctd.demo.auth;

import com.ctd.demo.config.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates rate limiting, authentication, session establishment, and audit events. */
@Service
public class LoginOrchestrationService {
    private final AuthService authentication;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final LoginRateLimiter limiter;
    private final ApplicationEventPublisher events;

    public LoginOrchestrationService(AuthService authentication, SecurityContextRepository contexts,
            SessionAuthenticationStrategy sessions, LoginRateLimiter limiter, ApplicationEventPublisher events) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.sessions = sessions;
        this.limiter = limiter;
        this.events = events;
    }

    @Transactional
    public LoginResponse login(LoginRequest credentials, HttpServletRequest request, HttpServletResponse response) {
        String username = authentication.normalizedUsername(credentials);
        String key = "username:" + username;
        reserve(key, response);
        try {
            // 2026-09-23: A concurrent password change cannot stamp an old-password login as current.
            var account = authentication.lockAccount(username);
            Authentication authenticated = authentication.authenticate(credentials);
            if (account == null) throw new org.springframework.security.authentication.BadCredentialsException("Invalid credentials");
            limiter.clear(key);
            LoginResponse result = authentication.currentUser(authenticated);
            sessions.onAuthentication(authenticated, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticated);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            request.getSession().setAttribute(CredentialStamp.ATTRIBUTE, CredentialStamp.of(account.getPasswordHash()));
            audit(username, request, AuthenticationAuditEvent.Outcome.SUCCESS);
            return result;
        } catch (AuthenticationException exception) {
            audit(username, request, AuthenticationAuditEvent.Outcome.FAILURE);
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void reserve(String key, HttpServletResponse response) {
        try {
            if (!limiter.allow(key)) {
                response.setHeader("Retry-After", Long.toString(Math.max(1, limiter.window().toSeconds())));
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many attempts. Please try again later.");
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void audit(String username, HttpServletRequest request, AuthenticationAuditEvent.Outcome outcome) {
        events.publishEvent(new AuthenticationAuditEvent(Instant.now(), username, request.getRemoteAddr(), outcome));
    }

    private ResponseStatusException unavailable(DataAccessException exception) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Login service is temporarily unavailable.", exception);
    }
}
