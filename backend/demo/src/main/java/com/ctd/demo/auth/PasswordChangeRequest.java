package com.ctd.demo.auth;

/** 2026-09-23: Never render credentials through a generated record toString. */
public record PasswordChangeRequest(String currentPassword, String newPassword, String confirmPassword) {
    @Override public String toString() { return "PasswordChangeRequest[REDACTED]"; }
}
