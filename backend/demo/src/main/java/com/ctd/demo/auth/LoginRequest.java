package com.ctd.demo.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Holds submitted credentials and applies required-field and size validation. */
public record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 72) String password) {
    /** Redacts credentials when the request is converted to a string. */
    @Override
    public String toString() { return "LoginRequest[credentials=REDACTED]"; }
}
