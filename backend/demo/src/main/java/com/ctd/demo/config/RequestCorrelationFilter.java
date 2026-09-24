package com.ctd.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds a safe request ID to responses and logging context for end-to-end tracing. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final String ORIGINAL_REMOTE_ADDRESS = "org.apache.catalina.AccessLog.RemoteAddr";
    private final Pattern trustedProxies;

    public RequestCorrelationFilter(
            @Value("${app.security.trusted-request-id-proxies:^$}") String trustedProxyPattern) {
        trustedProxies = Pattern.compile(trustedProxyPattern);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        Object originalAddress = request.getAttribute(ORIGINAL_REMOTE_ADDRESS);
        String peerAddress = originalAddress instanceof String value ? value : request.getRemoteAddr();
        boolean trustedPeer = trustedProxies.matcher(peerAddress).matches();
        String requestId = trustedPeer && supplied != null && supplied.matches("[A-Za-z0-9._-]{8,64}")
                ? supplied
                : UUID.randomUUID().toString();
        response.setHeader(HEADER, requestId);
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
