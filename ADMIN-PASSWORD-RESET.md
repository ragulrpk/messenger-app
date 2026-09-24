# Administrator password recovery — 2026-09-23

## Forgotten password

Select **Forgot password?** on the login screen. Contact the administrator through your established communication channel and provide your username. The administrator verifies your identity outside the app, resets your password, and shares the replacement securely. Sign in, then use **Settings → Change password** to choose your own password.

The login screen provides instructions; it does not submit a request or send notifications. No email configuration is required.

## Administrator steps

1. Sign in using the existing `ragul` account and its current password.
2. Open **Settings → Reset a user’s password**, also available at `/admin/password-reset`.
3. Enter the user's username, your own administrator password, a new password for the user, and confirmation.
4. Verify the user's identity through your established process, then select the acknowledgement checkbox.
5. Select **Reset password**. Credentials are cleared after success. Share the replacement securely and ask the user to change it after signing in.

New passwords must differ from the user's existing password and the submitted administrator password, contain at least 12 characters, and fit within 72 UTF-8 bytes. Password whitespace is preserved. For your own account, use **Change password**.

The replacement is an ordinary password: it does not expire, and changing it at first login is not enforced. The checkbox is an acknowledgement, not independent identity proof. If Ragul forgets their password, a trusted system operator must recover that account outside this UI.

## Backend behavior

- `AppUser.isAdministrator()` designates the exact canonical username `ragul` as the sole administrator. Reserve this username for the intended administrator; renaming or recreating it changes who holds these privileges. This is a single-administrator policy, not a general role-management system.
- Login and session lookup return an `administrator` boolean for navigation. Browser changes cannot grant backend access.
- `POST /api/v1/users/admin-password-reset` accepts `username`, `currentPassword` (the administrator's), `newPassword`, and `confirmPassword`. It requires authentication, CSRF, an enabled administrator account, a current credential stamp, and administrator-password verification.
- Authorization precedes target processing. Attempts use the existing limiter under `admin-password-reset:ragul`. Account locks serialize resets with login/password changes. Missing/disabled targets, self-reset, reuse, and invalid inputs are rejected without changing credentials.
- Success returns empty HTTP 204. Validation errors return 400, missing authentication 401, non-admin access or missing CSRF 403, and rate limiting 429.
- Only a new BCrypt hash is stored. The target's old sessions are rejected on their next HTTP request and sockets close on the next security check/heartbeat. Already executing requests may finish. The administrator stays signed in.
- After commit, a timestamped application log records the administrator name and target UUID. This is application-log auditing, not a database audit-history screen. Request objects redact credentials. The feature does not return/log passwords or place them in browser storage or URLs.

## Deployment and verification

Restart/rebuild backend and frontend. No database migration, mail dependency, automatic account creation, or existing-password change occurs during deployment. Refresh or sign in again to load Ragul's updated administrator flag. Update all backend instances before relying on the authorization/session behavior.

Verified: **35 backend tests and 36 frontend tests passed**, plus frontend lint, formatting, TypeScript checking, and production build. Coverage includes administrator authorization, forged client flags, CSRF, wrong administrator passwords, rate limiting, target isolation, old-session rejection, replacement login, navigation, validation, duplicate submission, and credential clearing. Backend log: `backend/demo/target/admin-password-reset-tests.log`.

Tests use synthetic accounts; no live account was reset. Docker-dependent production integration tests were not run. This workspace has no Git repository; dated records are maintained in both `CHANGE_HISTORY.md` files.

Design reference: [OWASP Forgot Password Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html), including offline recovery and session invalidation.
