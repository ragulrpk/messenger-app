package com.ctd.demo.config;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Uses Redis so every backend instance shares the same login-attempt counters. */
@Component
@Profile("!local & !test")
public class RedisLoginRateLimiter implements LoginRateLimiter {
    private static final DefaultRedisScript<Long> ATTEMPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]); "
                    + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return count;",
            Long.class);
    // DECR keeps the window's expiry. A key that already expired is left alone rather than going negative.
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 0 then return 0; end; "
                    + "local count = redis.call('DECR', KEYS[1]); "
                    + "if count <= 0 then redis.call('DEL', KEYS[1]); end; "
                    + "return count;",
            Long.class);
    private final StringRedisTemplate redis;
    private final int limit;
    private final Duration window;

    public RedisLoginRateLimiter(StringRedisTemplate redis,
            @Value("${app.security.login-rate-limit.attempts:20}") int limit,
            @Value("${app.security.login-rate-limit.window:60s}") Duration window) {
        this.redis = redis;
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Login rate-limit settings must be positive");
        }
        this.limit = limit;
        this.window = window;
    }

    @Override
    public boolean allow(String key) {
        Long count = redis.execute(ATTEMPT, List.of(redisKey(key)),
                Long.toString(window.toMillis()));
        return count != null && count <= limit;
    }

    @Override
    public void release(String key) { redis.execute(RELEASE, List.of(redisKey(key))); }

    @Override
    public Duration window() { return window; }

    private String redisKey(String key) { return "messenger:login-rate:" + hash(key); }

    /** Creates a collision-resistant Redis key without storing a readable client address. */
    private String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
