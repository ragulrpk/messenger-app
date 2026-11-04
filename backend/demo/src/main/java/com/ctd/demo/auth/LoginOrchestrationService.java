package com.ctd.demo.auth;

import com.ctd.demo.config.LoginRateLimiter;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Every way of signing in, and forgotten passwords.
 *  <ul>
 *  <li>Employees sign in with their login ID (GPF number or assigned username) and password.</li>
 *  <li>Administrators use a separate page with their password. Administrator accounts cannot use the employee
 *      page.</li>
 *  <li>A temporary password (imported or set by an administrator) only starts the first sign-in: the owner must
 *      choose a new password before getting a session.</li>
 *  <li>Forgot password: the owner enters their login ID and chooses a new password, which signs out every
 *      session.</li>
 *  </ul>
 *  Emailed one-time codes ({@link OneTimeCodes}, {@link CodeMailer}) are switched off for now: no step sends or
 *  checks a code.
 *  Between steps, the account being signed in is kept in the anonymous session, never sent to the browser. Every
 *  completed sign-in goes through {@link #complete}, which keeps one session per account (the newest wins), rotates
 *  the session ID, and starts the inactivity clock. */
@Service
public class LoginOrchestrationService {
    static final String PENDING = "messenger.pendingSignIn";
    /** Set only by the administrator sign-in, so administration needs a session from the administrator page. */
    public static final String ADMIN_SESSION = "messenger.adminSession";
    /** When the person last used the app; see IdleSessionFilter. */
    public static final String LAST_ACTIVITY = "messenger.lastActivity";
    private static final Duration PENDING_LIFETIME = Duration.ofMinutes(10);
    private final AuthService authentication;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final LoginRateLimiter limiter;
    private final ApplicationEventPublisher events;
    private final UserRepository users;
    private final SignInEvents history;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transactions;

    public LoginOrchestrationService(AuthService authentication, SecurityContextRepository contexts,
            SessionAuthenticationStrategy sessions, LoginRateLimiter limiter, ApplicationEventPublisher events,
            UserRepository users, SignInEvents history, PasswordEncoder encoder, TransactionTemplate transactions) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.sessions = sessions;
        this.limiter = limiter;
        this.events = events;
        this.users = users;
        this.history = history;
        this.encoder = encoder;
        this.transactions = transactions;
    }

    /** The sign-in or reset waiting for a new password; kept server-side in the anonymous session. */
    record Pending(UUID user, String loginId, OneTimeCodes.Purpose purpose, long startedAt) implements Serializable { }
    /** The browser's next step: choose a new password. */
    public record Challenge(String status, boolean passwordChangeRequired) { }
    public record VerifyRequest(String newPassword, String confirmPassword) {
        @Override public String toString() { return "VerifyRequest[REDACTED]"; }
    }
    public record ResetStartRequest(String username) { }

    // ---- Password step -------------------------------------------------------------------------------

    public Object login(LoginRequest credentials, HttpServletRequest request, HttpServletResponse response) {
        return passwordStep(credentials, request, response, false);
    }

    public Object adminLogin(LoginRequest credentials, HttpServletRequest request, HttpServletResponse response) {
        return passwordStep(credentials, request, response, true);
    }

    private Object passwordStep(LoginRequest credentials, HttpServletRequest request, HttpServletResponse response,
            boolean adminPage) {
        String loginId = authentication.normalizedUsername(credentials);
        String key = "username:" + loginId;
        reserve(key, response);
        try {
            return transactions.execute(status -> {
                // 2026-09-23: A concurrent password change cannot stamp an old-password login as current.
                var account = authentication.lockAccount(loginId);
                authentication.authenticate(credentials);
                if (account == null) throw new BadCredentialsException("Invalid credentials");
                limiter.release(key);
                // Checked only after the password, so the page reveals nothing about an account to a guesser.
                if (account.isAdministrator() != adminPage) {
                    throw adminPage
                            ? new SignInRejected(HttpStatus.FORBIDDEN, "NOT_ADMINISTRATOR",
                                    "This account is not an administrator. Use the employee sign-in page.")
                            : new SignInRejected(HttpStatus.FORBIDDEN, "USE_ADMIN_LOGIN",
                                    "Administrators sign in on the administrator sign-in page.");
                }
                if (!account.isMustChangePassword()) return complete(account, adminPage, request, response);
                var purpose = adminPage ? OneTimeCodes.Purpose.ADMIN_LOGIN : OneTimeCodes.Purpose.FIRST_LOGIN;
                return challenge(account, loginId, purpose, request);
            });
        } catch (AuthenticationException exception) {
            // Recorded after the rollback has released the account row.
            var account = users.findByUsername(loginId);
            history.record(account.map(AppUser::getId).orElse(null), loginId, account.isEmpty()
                    ? SignInEvents.Outcome.UNKNOWN_ACCOUNT : account.get().isEnabled()
                    ? SignInEvents.Outcome.WRONG_PASSWORD : SignInEvents.Outcome.DISABLED, request.getRemoteAddr());
            audit(loginId, request, AuthenticationAuditEvent.Outcome.FAILURE);
            throw exception;
        } catch (SignInRejected rejected) {
            if (rejected.code().equals("NOT_ADMINISTRATOR") || rejected.code().equals("USE_ADMIN_LOGIN"))
                history.record(users.findByUsername(loginId).map(AppUser::getId).orElse(null), loginId,
                        SignInEvents.Outcome.WRONG_LOGIN_PAGE, request.getRemoteAddr());
            throw rejected;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    // ---- New password step ---------------------------------------------------------------------------

    /** Finishes a sign-in that started with a temporary password: sets the new password, then signs in. */
    public LoginResponse verify(VerifyRequest body, HttpServletRequest request, HttpServletResponse response) {
        var pending = pending(request, OneTimeCodes.Purpose.ADMIN_LOGIN, OneTimeCodes.Purpose.FIRST_LOGIN);
        String hash = newPasswordHash(pending, body);
        return transactions.execute(status -> {
            var account = users.lockByUsername(pending.loginId()).filter(u -> u.getId().equals(pending.user()) && u.isEnabled())
                    .orElseThrow(() -> new SignInRejected(HttpStatus.UNAUTHORIZED, "SIGN_IN_FAILED", "Start signing in again."));
            boolean admin = pending.purpose() == OneTimeCodes.Purpose.ADMIN_LOGIN;
            if (admin && !account.isAdministrator())
                throw new SignInRejected(HttpStatus.FORBIDDEN, "NOT_ADMINISTRATOR", "This account is no longer an administrator.");
            account.changePasswordHash(hash);
            return complete(account, admin, request, response);
        });
    }

    // ---- Forgot password -----------------------------------------------------------------------------

    /** Starts a reset for an active account. Rate-limited per login ID and client. */
    public Challenge startReset(ResetStartRequest body, HttpServletRequest request, HttpServletResponse response) {
        String loginId = body.username() == null ? "" : body.username().strip().toLowerCase(java.util.Locale.ROOT);
        if (loginId.isEmpty() || loginId.length() > 64)
            throw new SignInRejected(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Enter your GPF number or username.");
        reserve("reset:" + loginId, response);
        try {
            var account = users.findByUsername(loginId).filter(AppUser::isEnabled)
                    .orElseThrow(() -> new SignInRejected(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                            "No active account has that GPF number or username."));
            return challenge(account, loginId, OneTimeCodes.Purpose.PASSWORD_RESET, request);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    /** Sets the new password. Every session of the account ends; the person then signs in. */
    public void completeReset(VerifyRequest body, HttpServletRequest request) {
        var pending = pending(request, OneTimeCodes.Purpose.PASSWORD_RESET);
        String hash = newPasswordHash(pending, body);
        transactions.executeWithoutResult(status -> {
            var account = users.lockByUsername(pending.loginId()).filter(u -> u.getId().equals(pending.user()) && u.isEnabled())
                    .orElseThrow(() -> new SignInRejected(HttpStatus.UNAUTHORIZED, "SIGN_IN_FAILED", "Start again."));
            // A new hash changes the credential stamp, which ends every existing session at its next request.
            account.changePasswordHash(hash);
            history.record(account.getId(), pending.loginId(), SignInEvents.Outcome.PASSWORD_RESET, request.getRemoteAddr());
        });
        request.getSession().removeAttribute(PENDING);
    }

    // ---- Shared steps --------------------------------------------------------------------------------

    /** Remembers the sign-in or reset in the anonymous session until the new password arrives. */
    private Challenge challenge(AppUser account, String loginId, OneTimeCodes.Purpose purpose, HttpServletRequest request) {
        request.getSession(true).setAttribute(PENDING,
                new Pending(account.getId(), loginId, purpose, System.currentTimeMillis()));
        return new Challenge("PASSWORD_CHANGE_REQUIRED", true);
    }

    private Pending pending(HttpServletRequest request, OneTimeCodes.Purpose... allowed) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(PENDING) instanceof Pending pending
                && List.of(allowed).contains(pending.purpose())
                && System.currentTimeMillis() - pending.startedAt() < PENDING_LIFETIME.toMillis())
            return pending;
        throw new SignInRejected(HttpStatus.BAD_REQUEST, "NO_PENDING_SIGN_IN", "That took too long. Start again.");
    }

    private String newPasswordHash(Pending pending, VerifyRequest body) {
        PasswordChangeService.validate(body.newPassword(), "newPassword", true);
        if (!body.newPassword().equals(body.confirmPassword()))
            throw new PasswordChangeRejected("confirmPassword", "Passwords do not match.");
        var account = users.findById(pending.user()).orElseThrow();
        if (encoder.matches(body.newPassword(), account.getPasswordHash()))
            throw new PasswordChangeRejected("newPassword", "Choose a password different from your current one.");
        return encoder.encode(body.newPassword());
    }

    /** Creates the session. The session strategy ends the account's other session (one device at a time),
     *  rotates the session ID and CSRF token, and registers the new session. */
    private LoginResponse complete(AppUser account, boolean admin, HttpServletRequest request, HttpServletResponse response) {
        var authenticated = UsernamePasswordAuthenticationToken.authenticated(account.getUsername(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        sessions.onAuthentication(authenticated, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        var session = request.getSession();
        session.removeAttribute(PENDING);
        session.setAttribute(CredentialStamp.ATTRIBUTE, CredentialStamp.of(account.getPasswordHash()));
        session.setAttribute(LAST_ACTIVITY, System.currentTimeMillis());
        if (admin) session.setAttribute(ADMIN_SESSION, Boolean.TRUE);
        else session.removeAttribute(ADMIN_SESSION);
        account.signedIn(Instant.now());
        history.record(account.getId(), account.getUsername(), SignInEvents.Outcome.SIGNED_IN, request.getRemoteAddr());
        audit(account.getUsername(), request, AuthenticationAuditEvent.Outcome.SUCCESS);
        return LoginResponse.from(account);
    }

    private void reserve(String key, HttpServletResponse response) {
        try {
            if (!limiter.allow(key)) {
                response.setHeader("Retry-After", Long.toString(Math.max(1, limiter.window().toSeconds())));
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many attempts. Please try again later.");
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void audit(String username, HttpServletRequest request, AuthenticationAuditEvent.Outcome outcome) {
        events.publishEvent(new AuthenticationAuditEvent(Instant.now(), username, request.getRemoteAddr(), outcome));
    }

    private ResponseStatusException unavailable(DataAccessException exception) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Login service is temporarily unavailable.", exception);
    }
}
