package com.ctd.demo.config;

import com.ctd.demo.auth.LoginOrchestrationService;
import com.ctd.demo.error.SecurityErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Signs a session out after a period without real use. Background requests (message polling, delivery
 *  receipts, notifications) keep the HTTP session itself alive, so inactivity is measured from the last
 *  activity the app reported (POST /api/v1/users/activity) or the sign-in, never from request traffic. */
public class IdleSessionFilter extends OncePerRequestFilter {
    private final long idleMillis;
    private final SecurityErrors errors;
    private final String message;

    public IdleSessionFilter(Duration idle, SecurityErrors errors) {
        this.idleMillis = idle.toMillis();
        this.errors = errors;
        this.message = "You were signed out after " + idle.toMinutes() + " minutes without activity.";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var session = request.getSession(false);
        if (session != null && auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            long now = System.currentTimeMillis();
            if (session.getAttribute(LoginOrchestrationService.LAST_ACTIVITY) instanceof Long last) {
                if (now - last >= idleMillis) {
                    session.invalidate();
                    SecurityContextHolder.clearContext();
                    errors.write(response, 401, "SESSION_IDLE", message);
                    return;
                }
            } else {
                // A session from before activity tracking starts its clock now.
                session.setAttribute(LoginOrchestrationService.LAST_ACTIVITY, now);
            }
        }
        chain.doFilter(request, response);
    }
}
