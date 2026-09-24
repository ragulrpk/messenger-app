package com.ctd.demo.auth;

/** Field-level error containing a safe message only, never the rejected password. */
public class PasswordChangeRejected extends RuntimeException {
    private final String field;
    public PasswordChangeRejected(String field, String message) { super(message); this.field = field; }
    public String field() { return field; }
}
