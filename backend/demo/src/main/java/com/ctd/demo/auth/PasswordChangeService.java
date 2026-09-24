package com.ctd.demo.auth;

import com.ctd.demo.config.LoginRateLimiter;
import com.ctd.demo.user.UserRepository;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 2026-09-23: Self-service change only; current-password proof is required and the actor comes from the session. */
@Service
public class PasswordChangeService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    public PasswordChangeService(UserRepository users, PasswordEncoder encoder, LoginRateLimiter limiter) {
        this.users = users; this.encoder = encoder; this.limiter = limiter;
    }

    @Transactional
    public void change(String username, Object stamp, PasswordChangeRequest request) {
        if (!limiter.allow("password-change:" + username))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        validate(request.currentPassword(), "currentPassword", false);
        validate(request.newPassword(), "newPassword", true);
        if (!request.newPassword().equals(request.confirmPassword()))
            throw new PasswordChangeRejected("confirmPassword", "Passwords do not match.");
        var user = users.lockByUsername(username).filter(u -> u.isEnabled())
                .orElseThrow(() -> new BadCredentialsException("Invalid session"));
        if (!CredentialStamp.matches(stamp, user.getPasswordHash()))
            throw new BadCredentialsException("Invalid session");
        if (!encoder.matches(request.currentPassword(), user.getPasswordHash()))
            throw new PasswordChangeRejected("currentPassword", "Current password is incorrect.");
        if (request.currentPassword().equals(request.newPassword()))
            throw new PasswordChangeRejected("newPassword", "Choose a different new password.");
        // Persisting a fresh salted hash changes the session stamp atomically, even if Redis is unavailable.
        user.changePasswordHash(encoder.encode(request.newPassword()));
    }

    static void validate(String password, String field, boolean replacement) {
        if (password == null || password.isBlank())
            throw new PasswordChangeRejected(field, "Enter a password.");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new PasswordChangeRejected(field, "Password must be 72 UTF-8 bytes or fewer.");
        if (replacement && password.length() < 12)
            throw new PasswordChangeRejected(field, "Use at least 12 characters.");
    }
}
