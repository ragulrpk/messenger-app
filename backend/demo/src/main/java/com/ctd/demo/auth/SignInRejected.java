package com.ctd.demo.auth;

import org.springframework.http.HttpStatus;

/** A refused sign-in or password-reset step with a stable code and a message safe to show the person. */
public class SignInRejected extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public SignInRejected(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
