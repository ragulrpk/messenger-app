package com.ctd.demo.config;

import com.ctd.demo.auth.CredentialStamp;
import com.ctd.demo.error.SecurityErrors;
import com.ctd.demo.user.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 2026-09-23: A changed password invalidates every older HTTP session across backend instances. */
public class CredentialStampFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final SecurityErrors errors;
    public CredentialStampFilter(UserRepository users, SecurityErrors errors) {
        this.users = users;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            var session = request.getSession(false);
            var user = users.findByUsername(auth.getName());
            if (session == null || user.isEmpty() || !user.get().isEnabled()
                    || !CredentialStamp.matches(session.getAttribute(CredentialStamp.ATTRIBUTE), user.get().getPasswordHash())) {
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                errors.write(response, 401, "SESSION_REVOKED", "Please sign in again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
