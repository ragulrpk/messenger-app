package com.ctd.demo.config;

import java.time.Duration;

/** Reserves login-attempt slots for privacy-safe limiter keys. */
public interface LoginRateLimiter {
    /** Reserves one slot before an attempt runs, so parallel attempts cannot overshoot the limit. */
    boolean allow(String key);
    /** Gives back one slot reserved by allow, after an attempt that succeeded. Only that slot: failures
     *  counted for the same key stay counted, so successful logins cannot reset an attacker's budget. */
    void release(String key);
    Duration window();
}
