package com.ctd.demo.config;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Provides bounded single-process login limiting for local development and tests. */
@Component
@Profile({"local", "test"})
public class InMemoryLoginRateLimiter implements LoginRateLimiter {
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Clock clock;
    private long windowStart;
    private static final int LIMIT = 20;
    private static final int MAX_CLIENTS = 10_000;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    public InMemoryLoginRateLimiter() {
        this(Clock.systemUTC());
    }

    public InMemoryLoginRateLimiter(Clock clock) {
        this.clock = clock;
        this.windowStart = clock.millis();
    }

    @Override
    public synchronized boolean allow(String key) {
        long now = clock.millis();
        if (now - windowStart >= 60_000) {
            attempts.clear();
            windowStart = now;
        }
        if (!attempts.containsKey(key) && attempts.size() >= MAX_CLIENTS) return false;
        int count = attempts.getOrDefault(key, 0);
        if (count >= LIMIT) return false;
        attempts.put(key, count + 1);
        return true;
    }

    @Override
    public synchronized void clear(String key) { attempts.remove(key); }

    @Override
    public Duration window() { return WINDOW; }
}
