package com.ctd.demo.auth;

import com.ctd.demo.config.LoginRateLimiter;
import com.ctd.demo.user.UserRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 2026-09-23: Administrator-assisted recovery; permissions and identity are checked on the server. */
@Service
public class AdminPasswordResetService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;

    public AdminPasswordResetService(UserRepository users, PasswordEncoder encoder, LoginRateLimiter limiter) {
        this.users = users; this.encoder = encoder; this.limiter = limiter;
    }

    @Transactional
    public UUID reset(String actor, Object stamp, ResetRequest request) {
        // Lock the administrator first, then the target, consistently with login/password change locking.
        var administrator = users.lockByUsername(actor).filter(u -> u.isEnabled() && u.isAdministrator())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!CredentialStamp.matches(stamp, administrator.getPasswordHash()))
            throw new BadCredentialsException("Invalid session");
        if (!limiter.allow("admin-password-reset:" + actor))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        PasswordChangeService.validate(request.currentPassword(), "currentPassword", false);
        if (!encoder.matches(request.currentPassword(), administrator.getPasswordHash()))
            throw new PasswordChangeRejected("currentPassword", "Your administrator password is incorrect.");
        String target = request.username() == null ? "" : request.username().trim().toLowerCase(Locale.ROOT);
        if (target.isBlank() || target.length() > 64)
            throw new PasswordChangeRejected("username", "Enter a username of 64 characters or fewer.");
        if (target.equals(actor))
            throw new PasswordChangeRejected("username", "Use Change password to update your own account.");
        PasswordChangeService.validate(request.newPassword(), "newPassword", true);
        if (!request.newPassword().equals(request.confirmPassword()))
            throw new PasswordChangeRejected("confirmPassword", "Passwords do not match.");
        if (request.newPassword().equals(request.currentPassword()))
            throw new PasswordChangeRejected("newPassword", "Do not use your administrator password for another account.");
        var user = users.lockByUsername(target).filter(u -> u.isEnabled())
                .orElseThrow(() -> new PasswordChangeRejected("username", "No enabled account has that username."));
        if (encoder.matches(request.newPassword(), user.getPasswordHash()))
            throw new PasswordChangeRejected("newPassword", "Choose a different new password.");
        // A new salted hash invalidates all target HTTP and WebSocket credential stamps after commit.
        user.changePasswordHash(encoder.encode(request.newPassword()));
        return user.getId();
    }

    public record ResetRequest(String username, String currentPassword, String newPassword, String confirmPassword) {
        @Override public String toString() { return "AdminPasswordResetRequest[REDACTED]"; }
    }
}
