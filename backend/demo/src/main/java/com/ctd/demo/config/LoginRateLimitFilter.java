package com.ctd.demo.config;

import com.ctd.demo.error.SecurityErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Limits login attempts per client within a time window before requests reach authentication. */
public class LoginRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);
    private final LoginRateLimiter limiter;
    private final SecurityErrors errors;

    /** Receives the environment-specific shared or local limiter. */
    public LoginRateLimitFilter(LoginRateLimiter limiter, SecurityErrors errors) {
        this.limiter = limiter;
        this.errors = errors;
    }

    /** Applies this filter only to login POST requests. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/api/v1/users/login".equals(request.getServletPath());
    }

    /** Returns a rate-limit response or passes the request to the next filter. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Tomcat resolves forwarded addresses only for configured trusted proxy peers.
        // Never read X-Forwarded-For directly here.
        try {
            String key = "client:" + request.getRemoteAddr();
            if (!limiter.allow(key)) {
                response.setHeader("Retry-After", Long.toString(Math.max(1, limiter.window().toSeconds())));
                errors.write(response, 429, "RATE_LIMITED", "Too many attempts. Please try again later.");
                return;
            }
            chain.doFilter(request, response);
            if (response.getStatus() >= 200 && response.getStatus() < 300) limiter.clear(key);
        } catch (DataAccessException exception) {
            log.error("Login rate limiter unavailable", exception);
            errors.write(response, 503, "SERVICE_UNAVAILABLE", "Login is temporarily unavailable.");
        }
    }
}
