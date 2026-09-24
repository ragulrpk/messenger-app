# Change password — 2026-09-23

## Using the feature

1. Sign in, open chat Settings, and select **Change password**. The form is also on the profile page.
2. Enter the current password, new password, and confirmation. **Show passwords** reveals only what you type into these fields.
3. Use a different new password with at least 12 characters and at most 72 UTF-8 bytes. Spaces are preserved. The byte limit can be reached sooner with non-ASCII characters.
4. Submit. On success, the login screen displays **Password changed. Sign in with your new password.**

This changes the signed-in user's own password. It does not recover/decrypt an old password or reset someone else's account. For forgotten passwords, the `ragul` administrator can now use the separate [administrator reset process](ADMIN-PASSWORD-RESET.md).

Wrong-current-password, validation, and confirmation errors appear beside the relevant fields. Submission is disabled while pending. Clear removes the entered values. A connection failure may happen after a successful server commit, so the form explains that signing in with the new password may be necessary.

## API and security behavior

`POST /api/v1/users/password` requires an authenticated session and a valid CSRF token. The JSON body contains `currentPassword`, `newPassword`, and `confirmPassword`. It does not accept a target account; the actor comes from the session.

The backend rate-limits attempts with a separate account-scoped `password-change:` key using the existing limiter configuration. It locks the user's row, verifies the current password, applies length/confirmation/reuse checks, and stores a fresh BCrypt hash using the existing encoder. The successful response is HTTP 204. Field errors return 400, invalid authentication 401, missing/invalid CSRF 403, and rate limiting 429.

Credential request objects redact their `toString`. Passwords and hashes are not returned to the browser, written to browser storage, or placed in URLs.

## What happens to existing sessions

At login, the server binds the session to a SHA-256 fingerprint of the stored salted BCrypt hash. Each authenticated HTTP request compares that binding with the current database value. WebSocket delivery and heartbeat checks do the same.

Changing the stored hash atomically invalidates the bindings of all older sessions, including sessions on other backend instances. The current session is invalidated immediately after commit. Other sessions are rejected and invalidated on their next HTTP request; their existing sockets close at the next security check. Idle session records can remain until accessed or expired, but the application will no longer authorize them. Requests already executing when a password changes may finish.

Login and password changes lock the same account row so an old-password login cannot acquire a new binding during a concurrent change. Concurrent password changes recheck the binding inside that lock. This avoids depending on Redis session enumeration or successful cross-store cleanup to enforce the password change.

## Deployment impact

- Restart/rebuild backend and frontend together. No database migration is required.
- Sessions established before this feature do not have a credential binding and will require a fresh login on deployment.
- In a deployment with multiple backend instances, all must run the updated credential checks before relying on global invalidation. An old instance does not enforce the new binding.
- Authenticated HTTP requests now perform an account lookup for the credential check. Include this additional database work in capacity testing.
- Profile details remain read-only; only the new password form updates account data.
- Production continues to require HTTPS and correct Redis/session/ingress configuration.

## Files and verification

Frontend: `ChangePassword.jsx`, `passwordValidation.js`, profile page/styles, chat settings link, `authService.js`, `AuthProvider.jsx`, and login success notice.

Backend: password request/service/rejection classes, controller endpoint, account hash update and row-lock query, `CredentialStamp`, `CredentialStampFilter`, login transaction/binding, and WebSocket binding checks.

Verified locally: **32 backend tests and 31 frontend tests passed**; ESLint, Prettier, TypeScript, and the Vite production build passed. Coverage includes wrong current password, byte limits, confirmation/reuse, CSRF, anonymous requests, rate limits, account isolation, concurrent changes, old-password rejection, other-browser invalidation, WebSocket closure, frontend pending/error/success behavior, and credential storage checks.

The WebSocket password-change test is inherited by the Docker-required production-profile integration suite. PostgreSQL/Redis container and deployed browser/HTTPS ingress checks remain unexecuted locally because Docker is unavailable. Local backend tests use isolated H2 databases and synthetic test credentials; no existing user passwords were changed by this implementation work.

Design reference: [OWASP guidance for change-password flows](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#change-password-feature).
