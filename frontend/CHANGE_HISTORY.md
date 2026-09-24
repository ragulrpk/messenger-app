# Frontend Change History

This file records frontend and frontend-container changes, their purpose, and their observable functionality. Add new entries at the top using the same format: date, files, change, functionality, and verification.

## 2026-09-23 — Forgot-password instructions and administrator reset form

- Added expandable **Forgot password?** instructions to login for administrator-assisted recovery without email or an unauthenticated reset endpoint.
- Added the administrator Settings link and protected `/admin/password-reset` screen. Requires target username, administrator password, new password/confirmation, and identity-verification acknowledgement. Includes validation, masked/show-password controls, Clear, pending state, duplicate-submit prevention, errors, and credential clearing after success.
- Preserved the backend administrator flag in the profile and added a CSRF/cookie-protected API call. Backend authorization remains authoritative. Added dated comments.
- Verification: all 36 frontend tests passed; lint, formatting, TypeScript, and production build passed. Usage and limitations: `../ADMIN-PASSWORD-RESET.md`.

## 2026-09-23 — Change password form in account settings

- Added a Settings link to `/profile#password` and a responsive profile form with current/new/confirmation fields, show-passwords control, Clear, pending state, and field-level errors. Validation preserves spaces, enforces 12-character minimum/72-byte UTF-8 maximum for new passwords, and rejects mismatch/reuse.
- Added the CSRF-protected password-change API call and `AuthProvider.updatePassword`. Successful changes clear the local account and notify other tabs; the login page displays a success notice. Credentials stay out of browser storage, URLs, and navigation state.
- Added dated comments and tests for validation, API payload/cookies/CSRF, backend errors, duplicate-submit prevention, and successful logout/notice.
- Verification: 31 frontend tests passed; lint, formatting, TypeScript and production build passed. Usage and deployment impact: `../PASSWORD-CHANGE.md`.

## 2026-09-23 — WebSocket live refresh and reconnect recovery

- Added `src/services/chatEvents.ts`: native WS/WSS connection, server-hint handling, heartbeat watchdog, bounded exponential reconnect, visible/online reconciliation, and teardown on logout/account change.
- Updated `useMessaging.ts`: live hints refresh the list and active history; in-flight hints queue a follow-up; missed messages catch up through multiple pages without waiting for the next poll. Existing REST sends, retry identifiers, pagination, and polling fallback remain.
- Updated `vite.config.js` and `nginx.conf` for WebSocket upgrades. Browser Origin is preserved; proxy forwarding headers are overwritten. Added an explicit Node process import to correct existing lint errors.
- Added dated functionality comments, transport tests in `chatEvents.test.js`, and a chat integration assertion proving a hint updates messages without a polling tick. Updated the npm test script.
- Verification: 28 frontend tests passed; ESLint, Prettier, TypeScript, and Vite production build passed. Production Nginx runtime/HTTPS ingress validation remains outstanding because Docker is unavailable.

## 2026-09-22 — P2 request-size protection

- `nginx.conf` sets `client_max_body_size 64k`. Requests larger than this limit are rejected at the frontend reverse proxy before they consume backend resources.
- The production frontend image was rebuilt successfully, validating the Nginx configuration and the React production bundle.

## 2026-09-22 — Docker deployment and backend proxying

- `Dockerfile` performs a multi-stage production build: Node installs dependencies and builds the React application, then unprivileged Nginx serves the generated static files.
- `Dockerfile.dev` supports the Vite development server for container-based development.
- `nginx.conf` serves the React single-page application and proxies backend API traffic to the Spring Boot service, allowing the browser to use one frontend origin for the UI and API.
- `.dockerignore` keeps dependency folders, generated output, and other unnecessary files out of the Docker build context.
- The production Compose configuration supplies the backend destination and publishes the frontend service for browser access.

## 2026-09-22 — Authentication and chat integration

- `AuthProvider` and `authService` manage the authenticated user state and session-based login/logout calls.
- `chatService` connects the conversation directory, conversation list, history, direct-conversation creation, and message-send operations to the backend API.
- The chat page and component set provide conversation selection, searchable contacts, message history, message composition, presence display, settings, and empty-state/error handling.
- Frontend tests cover the application shell, authentication provider/service, chat state/components, and theme behavior.

## Maintenance rule

For every frontend change, add an entry containing:

1. The date and scope.
2. The affected component, service, configuration, or container file.
3. What behavior changed and why.
4. The tests or build command used to verify it.
