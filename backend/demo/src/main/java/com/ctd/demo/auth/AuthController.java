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

    /** Employee sign-in with GPF number or username. Returns {user} when signed in, or
     *  {status:"PASSWORD_CHANGE_REQUIRED", passwordChangeRequired} when a temporary password must be replaced. */
    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest credentials,
                        HttpServletRequest request, HttpServletResponse response) {
        return loginService.login(credentials, request, response);
    }

    /** Administrator sign-in with a password. */
    @PostMapping("/admin-login")
    public Object adminLogin(@Valid @RequestBody LoginRequest credentials,
                             HttpServletRequest request, HttpServletResponse response) {
        return loginService.adminLogin(credentials, request, response);
    }

    /** Finishes the sign-in in progress with the new password that replaces a temporary one. */
    @PostMapping("/login/verify")
    public LoginResponse verify(@RequestBody LoginOrchestrationService.VerifyRequest body,
                                HttpServletRequest request, HttpServletResponse response) {
        return loginService.verify(body, request, response);
    }

    /** Forgot password: starts a reset for the account with this GPF number or username. */
    @PostMapping("/password-reset/start")
    public LoginOrchestrationService.Challenge startReset(@RequestBody LoginOrchestrationService.ResetStartRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return loginService.startReset(body, request, response);
    }

    /** Sets a new password for the reset in progress; every session of the account ends. */
    @PostMapping("/password-reset/complete")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void completeReset(@RequestBody LoginOrchestrationService.VerifyRequest body, HttpServletRequest request) {
        loginService.completeReset(body, request);
    }

    /** The person is using the app: typing, clicking or scrolling in a visible tab. Only this keeps a session
     *  from timing out; background requests do not. */
    @PostMapping("/activity")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void activity(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.setAttribute(LoginOrchestrationService.LAST_ACTIVITY, System.currentTimeMillis());
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
