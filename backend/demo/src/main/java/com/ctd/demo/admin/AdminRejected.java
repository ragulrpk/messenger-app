package com.ctd.demo.admin;

import org.springframework.http.HttpStatus;

/** A refused administrative request with a stable code and a message safe to show the administrator. */
public class AdminRejected extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AdminRejected(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
