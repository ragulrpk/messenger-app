package com.ctd.demo.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 2026-09-23: Session- and CSRF-protected administrator recovery endpoint; never returns a password. */
@RestController
public class AdminPasswordResetController {
    private static final Logger log = LoggerFactory.getLogger(AdminPasswordResetController.class);
    private final AdminPasswordResetService resets;
    public AdminPasswordResetController(AdminPasswordResetService resets) { this.resets = resets; }

    @PostMapping("/api/v1/users/admin-password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(Authentication authentication, HttpServletRequest request,
            @RequestBody AdminPasswordResetService.ResetRequest body) {
        var session = request.getSession(false);
        var targetId = resets.reset(authentication.getName(),
                session == null ? null : session.getAttribute(CredentialStamp.ATTRIBUTE), body);
        // Service transaction has committed. Audit only actor and target ID, never credentials or request body.
        log.info("Administrator password reset completed actor={} targetUserId={}", authentication.getName(), targetId);
    }
}
