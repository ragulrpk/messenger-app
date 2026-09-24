package com.ctd.demo.config;

import java.time.Duration;

/** Reserves and clears login-attempt slots for privacy-safe limiter keys. */
public interface LoginRateLimiter {
    boolean allow(String key);
    void clear(String key);
    Duration window();
}
