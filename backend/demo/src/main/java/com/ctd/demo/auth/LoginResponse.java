package com.ctd.demo.auth;

import com.ctd.demo.user.AppUser;
import java.util.UUID;

/** Defines the public user data returned after login and session lookup. */
public record LoginResponse(UserView user) {
    public record UserView(UUID id, String username, String name, boolean administrator) { }

    /** Maps a stored account to a response without exposing its password hash. */
    public static LoginResponse from(AppUser user) {
        return new LoginResponse(new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.isAdministrator()));
    }
}
