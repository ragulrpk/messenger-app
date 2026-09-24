package com.ctd.demo.auth;

import com.ctd.demo.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Validates login credentials and retrieves the authenticated user's profile. */
@Service
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final UserRepository users;

    /** Receives the account store and Spring authentication manager. */
    public AuthService(AuthenticationManager authenticationManager, UserRepository users) {
        this.authenticationManager = authenticationManager;
        this.users = users;
    }

    /** Authenticates without revealing whether the account or password failed. */
    public Authentication authenticate(LoginRequest request) {
        String username = normalizedUsername(request);
        // BCrypt's limit is bytes, not Java characters. Never silently truncate a password.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, request.password()));
    }

    /** Produces the canonical username used by authentication and rate limiting. */
    public String normalizedUsername(LoginRequest request) {
        return request.username().trim().toLowerCase(Locale.ROOT);
    }

    /** 2026-09-23: Called inside the login transaction before checking credentials. */
    public com.ctd.demo.user.AppUser lockAccount(String username) {
        return users.lockByUsername(username).orElse(null);
    }

    /** Builds a public profile for an enabled authenticated account. */
    public LoginResponse currentUser(Authentication authentication) {
        return users.findByUsername(authentication.getName())
                .filter(user -> user.isEnabled())
                .map(LoginResponse::from)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
    }
}
