package com.ctd.demo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** Exposes CSRF, login, and current-user endpoints backed by server sessions. */
@RestController
@RequestMapping("/api/v1/users")
public class AuthController {
    private final AuthService authService;
    private final LoginOrchestrationService loginService;
    private final PasswordChangeService passwords;

    /** Receives the authentication and session components used by the endpoints. */
    public AuthController(AuthService authService, LoginOrchestrationService loginService, PasswordChangeService passwords) {
        this.authService = authService;
        this.loginService = loginService;
        this.passwords = passwords;
    }

    /** Returns the CSRF token required for state-changing requests. */
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    /** Authenticates credentials, rotates the session, and saves its security context. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest credentials,
                               HttpServletRequest request, HttpServletResponse response) {
        return loginService.login(credentials, request, response);
    }

    /** Returns the profile belonging to the current authenticated session. */
    @GetMapping("/me")
    public LoginResponse me(Authentication authentication) {
        return authService.currentUser(authentication);
    }

    /** 2026-09-23: Commit the change before ending the current login; other sessions fail stamp checks. */
    @PostMapping("/password")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void changePassword(Authentication authentication, @RequestBody PasswordChangeRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        passwords.change(authentication.getName(), session == null ? null : session.getAttribute(CredentialStamp.ATTRIBUTE), body);
        new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler()
                .logout(request, response, authentication);
        new org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler("JSESSIONID", "SESSION")
                .logout(request, response, authentication);
    }

    public record CsrfResponse(String headerName, String token) { }
}
