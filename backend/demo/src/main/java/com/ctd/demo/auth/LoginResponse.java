package com.ctd.demo.auth;

import com.ctd.demo.user.AppUser;
import java.time.Instant;
import java.util.UUID;

/** Defines the public user data returned after login and session lookup. */
public record LoginResponse(UserView user) {
    /** mustChangePassword: the password is temporary and nothing else works until it is replaced.
     *  passwordResetAt: when an administrator last reset it, so the owner always learns that it happened. */
    public record UserView(UUID id, String username, String name, boolean administrator,
                           boolean mustChangePassword, Instant passwordResetAt,
                           String gpfNumber, String email, String phoneNumber, String cadre) { }

    /** Maps a stored account to a response without exposing its password hash. */
    public static LoginResponse from(AppUser user) {
        return new LoginResponse(new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.isAdministrator(),
                user.isMustChangePassword(), user.getPasswordResetAt(),
                user.getGpfNumber(), user.getEmail(), user.getMobile(), user.getCadre()));
    }
}
