# Messenger functionality and production-readiness report

Reviewed: 24 September 2026. Scope: current frontend, backend, persistence, security, tests, container definitions, CI configuration and operational documentation in the supplied workspace.

**Verdict: core one-to-one text messaging is implemented, but production release is not yet approved by the available evidence.** This is an implemented MVP with passing local checks, several limited UI-only features, and outstanding deployment and operational validation. “Implemented” below means a connected code path exists; it does not certify a deployed environment.

No completion percentage is assigned because there is no agreed product acceptance checklist. Missing optional messenger features are distinguished from blockers to releasing the existing text-chat scope.

## 1. Completed functionality

| Functionality | Frontend implementation | Backend implementation | Assessment |
| --- | --- | --- | --- |
| Login | Form validation, password visibility, errors, pending state | Normalized username, BCrypt cost 12, enabled-account checks | Implemented |
| Restore login and protect pages | Initial session lookup, protected chat/profile/admin routes | Authenticated current-user endpoint | Implemented |
| Logout and account isolation | Error/retry handling, cross-tab session signals, chat keyed by user ID | Session invalidation and security-context clearing | Implemented |
| Session security | Cookie requests, CSRF exchange, expiry revalidation | CSRF, session rotation, credential stamps, concurrency limit, secure cookie configuration | Implemented; Redis production behavior still needs release validation |
| Change own password | Profile/settings form, validation, errors and sign-in-again flow | Current-password verification, length/byte limits, locked update, old-session rejection | Implemented |
| Administrator password reset | Restricted form, administrator reauthentication and identity-verification reminder | Server-side administrator check, target validation, password update and success audit | Implemented for the single designated administrator |
| Find people | Debounced search, loading/error/retry states | Enabled-user directory, literal wildcard escaping, maximum 50 results | Implemented; refine search to find more matches |
| Open direct conversation | Select search result and open/reveal chat | Unique pair key, transaction and account locking | Implemented |
| Recent conversations | Last-message previews, pagination and sorting | Membership-filtered cursor pages | Implemented |
| Send text | Enter/Shift+Enter, 4,000-character limit, pending/error/retry controls | Authorized sender, validation, durable insert | Implemented |
| Safe send retries | Stable client UUID while the unchanged failed draft stays mounted | Unique retry key, same-message deduplication, conflict on changed text | Implemented; retry state does not survive reload |
| Message history | Selected conversation history and “Load older messages” | Before/after sequence cursors, 50-message pages | Implemented |
| Live updates | WebSocket reconnect/watchdog, focus/online recovery, polling fallback | Authenticated exact-origin socket, participant-only refresh hints after commit | Implemented; sockets signal refresh, REST returns messages |
| Reconnect catch-up | Drains newer pages with a bounded batch and continuation | Ordered forward history; conversation lock before sequence allocation | Implemented in source |
| Cross-instance design | Same client flow | Redis sessions and Pub/Sub notification fan-out | Implemented architecture; multi-instance behavior not demonstrated in this review |
| Theme | Light/dark, localStorage and cross-tab synchronization | Not needed | Complete as a browser preference |
| Basic profile | Name, username and initials avatar | Public identity response excludes password hash | Implemented |
| Error and responsive UI | Error boundary, loading/empty states, mobile CSS, labels and focus styles | Consistent common API errors | Implemented; no full accessibility/browser audit |

Main evidence: [routing](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/src/App.jsx), [authentication state](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/src/context/AuthProvider.jsx), [messaging hook](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/src/pages/chat/useMessaging.ts), [chat service](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/main/java/com/ctd/demo/chat/ChatService.java), [security](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/main/java/com/ctd/demo/config/SecurityConfig.java), [password service](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/main/java/com/ctd/demo/auth/PasswordChangeService.java), [administrator reset](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/main/java/com/ctd/demo/auth/AdminPasswordResetService.java), [WebSocket handler](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/main/java/com/ctd/demo/chat/ChatWebSocketHandler.java).

## 2. Partial functionality and visible limitations

| Feature | Actual current behavior | Work required for a complete shared feature |
| --- | --- | --- |
| Pin/unpin | React state only; resets on reload | Per-user conversation preference storage and API |
| Mute/unmute | Local flag; no notification service | Persist preference and enforce it in notification delivery |
| Mark read/unread | Manual local 0/1 marker; not incoming unread counts | Per-member read cursor, unread counts and event synchronization |
| Remove conversation | Hides locally; does not delete history | Define archive/delete semantics and implement persisted action |
| Online/offline/busy status | Local selector; other users do not receive it | Presence service with expiry and authorized subscriptions |
| Personal note | Local component state | Persist note and expose it according to visibility rules |
| Drafts | Memory only; retained across ordinary profile navigation | Durable storage/offline queue if required; scope storage to account |
| Extended profile | UI for phone, email, dates and organization fields | Database fields, DTOs, validation and editing API/UI |
| Attachments | Renderer/type support exists; upload button is disabled | Storage, upload/download authorization, limits and scanning |
| Groups | Mock/type/presentation structures exist | Group schema, membership authorization and creation/management flows |
| TN Mail | Opens an external website | No integrated mailbox or SSO implemented |
| Forgot password | Instructions to contact administrator | No self-service recovery-token/email flow |
| Administration | Only password reset; administrator is username `ragul` | Role management and provisioning lifecycle if broader administration is intended |

Evidence: [local chat state](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/src/pages/chat/ChatPage.tsx:31), [conversation actions](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/src/components/chat/chatState.ts), [composer](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/src/components/chat/MessageComposer.tsx), [profile](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/src/pages/profile/Profile.tsx), [public user response](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/main/java/com/ctd/demo/auth/LoginResponse.java), [administrator designation](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/main/java/com/ctd/demo/user/AppUser.java:42).

## 3. Not implemented

The reviewed application has no connected implementation of public signup, user approval/CRUD administration, MFA, SSO, profile editing/avatar upload, group chat, file upload, voice/video calls, voice messages, typing indicators, shared presence, delivery/read receipts, push/email notifications, message editing/deletion, reactions, replies/threads, forwarding, message-content search, user export, retention/deletion workflows or end-to-end message encryption.

Messages are stored as text in PostgreSQL. Transport encryption depends on the external TLS deployment. These features should enter the backlog only where product requirements demand them; they are not all prerequisites for a limited internal text messenger.

## 4. Production findings and required action

### High: TLS ingress forwarding loses client identity and external protocol

Both API and WebSocket Nginx locations overwrite `X-Forwarded-For` with `$remote_addr` and `X-Forwarded-Proto` with `$scheme`. With the documented TLS load balancer in front, these can become the load balancer address and internal HTTP protocol. Independent users then share an IP rate-limit bucket, and the backend does not receive the external scheme correctly.

Configure trusted ingress peers and real-client-address processing, preserve the verified external scheme, and reject untrusted forwarding headers. Narrow the broad 172.* backend trust expression to the deployed proxy network. Verify two real clients behind the actual ingress, secure cookies and WSS.

Evidence: [Nginx forwarding](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/nginx.conf:27), [backend proxy trust](C:/RAGUL/Projects/Messenger/Documents/messenger-app/compose.production.yml:48). Source/configuration finding; not a reproduced deployed outage.

### High: workspace repository layout does not support the supplied CI as-is

The workspace root and frontend are not Git repositories. The detected repository root is the backend/demo directory, and many backend implementation files are untracked. The root-level GitHub Actions workflow lies outside that repository and assumes a checkout containing both frontend and backend/demo.

Establish the intended repository boundary, include all required source/configuration, and demonstrate CI from a fresh checkout. Do not treat the workflow's existence on disk as proof that it is running remotely. No Git configuration or staging was changed during review.

Evidence: local `git rev-parse`/`git status` results and [CI workflow](C:/RAGUL/Projects/Messenger/Documents/messenger-app/.github/workflows/ci.yml).

### High validation gap: production infrastructure has not been exercised here

Docker is unavailable. The PostgreSQL/Redis suites, container builds, full deployed browser flow and TLS proxy path were not run. Local H2 tests cannot validate PostgreSQL-specific migrations, Redis indexed sessions or cross-node behavior.

The new [production WebSocket suite](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/test/java/com/ctd/demo/ChatWebSocketProductionIT.java) correctly selects a non-local/non-test profile. However, [general infrastructure suite](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/test/java/com/ctd/demo/ProductionInfrastructureIT.java:33) still uses the test profile. Its readiness-named test checks containers, SQL and a limiter, not the HTTP readiness endpoint. No two-backend test was found.

Required release evidence: both integration suites passing; two instances sharing a login and receiving events; cross-instance password/reset revocation; maximum-session enforcement; readiness during database/Redis failure; controlled concurrent distinct sends with an intervening history read; browser login/send/reconnect/logout through real ingress.

### Medium: monitoring is configured but not connected end to end

Prometheus export exists, but the security chain requires authentication for it and disables Basic authentication. Nginx does not proxy `/actuator/prometheus`. A conventional unauthenticated scrape through the frontend will therefore not retrieve metrics. This is not a reason to expose metrics publicly.

Provide an internal management route and explicit scraper authentication/network policy, then demonstrate successful scrape and alert delivery. Configure and test PostgreSQL backups, restoration and rollback; current documentation is a runbook, not evidence of an operating backup system.

Evidence: [security matchers](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/main/java/com/ctd/demo/config/SecurityConfig.java), [Nginx routes](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/nginx.conf), [operations runbook](C:/RAGUL/Projects/Messenger/Documents/messenger-app/PRODUCTION.md).

### Medium: account provisioning and administrator lifecycle are limited

Production has no account-creation endpoint; bootstrap creation runs only under the local profile. A fresh production database therefore needs a separate provisioning process. Administrator authority is tied to the exact username `ragul`, not a stored role. This is enforced on the server and is not a client-side authorization bypass, but it prevents delegated administration and creates an operational recovery dependency.

Document secure account provisioning and administrator recovery. If multi-admin support is required, add explicit roles and audited role assignment. Administrator reset tells the person to change the password later, but the server has no mandatory next-login password-change flag.

### Medium: capacity and abuse resistance remain unproven

Login and password operations have rate limits, but no message-send/directory quotas were found. Each visible chat retains 3-second message and 5-second list polling even with WebSockets; roughly 0.53 scheduled REST reads per second per active tab before sends/events. Socket validation also reads session/account state. Message arrays and DOM rendering grow as history is loaded.

Define concurrent-user and message-rate targets. Load-test REST, socket fan-out, database locks/pool, Redis and reconnect storms. Add quotas and measured optimizations; consider virtualized history and less frequent reconciliation when the socket is healthy. Do not infer a supported user count from configured Tomcat limits.

Evidence: [polling](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/src/pages/chat/useMessaging.ts:123), [message rendering](C:/RAGUL/Projects/Messenger/Documents/messenger-app/frontend/src/components/chat/MessageList.tsx:33), [socket validation](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/main/java/com/ctd/demo/chat/ChatWebSocketHandler.java).

### Lower-priority correctness and maintainability findings

- A base64-valid conversation cursor with an invalid timestamp reaches `Instant.parse`. The catch covers `IllegalArgumentException` and `IndexOutOfBoundsException`, but not `DateTimeParseException`; the generic exception handler can return 500 instead of 400. Add explicit handling and a regression test. This is a source finding, not an HTTP reproduction. See [cursor decoder](C:/RAGUL/Projects/Messenger/Documents/messenger-app/backend/demo/src/main/java/com/ctd/demo/chat/ChatService.java:134).
- TypeScript checking covers TS/TSX, while `checkJs` is false; passing typecheck does not statically validate all authentication JSX/JS. Component tests provide some coverage.
- A WebSocket 1008 close for too many connections is treated like session expiry by the client, followed by revalidation/retry. Separate connection-limit feedback from revoked-session feedback if many tabs are supported.
- Existing functionality documentation is stale. It incorrectly describes WebSockets and password workflows as absent and repeats resolved defects. Keep this report as the current assessment and revise older documents before handoff.

## 5. Persistence and API inventory

| Data | Storage |
| --- | --- |
| Accounts, password hashes, conversations, membership, messages | PostgreSQL |
| Production HTTP sessions and rate counters | Redis |
| Live cross-instance hints | Redis Pub/Sub; polling recovers missed hints |
| Theme | Browser localStorage |
| Pin/mute/read/hide/status/note, drafts and retry UUIDs | Browser memory |
| Extended profile fields | Not in current backend model |

Flyway migrations create account and messaging tables, foreign keys, retry uniqueness, search indexes and a covering message-history index. Hibernate validates the schema. PostgreSQL migrations include `pg_trgm`; release deployment must supply suitable migration privileges. Production Compose provides persistent volumes, health checks and restart policies, but a single-host database/Redis deployment is not high availability.

Connected endpoints:

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /api/v1/users/csrf | CSRF token |
| POST | /api/v1/users/login | Login |
| GET | /api/v1/users/me | Current identity |
| POST | /api/v1/users/logout | Logout |
| POST | /api/v1/users/password | Own password change |
| POST | /api/v1/users/admin-password-reset | Administrator reset |
| GET | /api/v1/chat/users | Directory search |
| GET | /api/v1/chat/conversations | Conversation pages |
| POST | /api/v1/chat/conversations/direct | Open/create direct chat |
| GET | /api/v1/chat/conversations/{id}/messages | History/catch-up |
| POST | /api/v1/chat/conversations/{id}/messages | Send text |
| WebSocket | /api/v1/chat/events | Authenticated refresh hints |

Operational paths: frontend /healthz; backend /actuator/health, /actuator/health/liveness, /actuator/health/readiness and /actuator/prometheus, subject to routing and authentication configuration.

## 6. Verification on this review

| Check | Result |
| --- | --- |
| Frontend automated tests | PASS: 36 tests |
| ESLint | PASS |
| Prettier format check | PASS |
| TypeScript and Vite production build | PASS |
| npm production dependency audit | PASS: 0 reported vulnerabilities |
| Backend Maven tests | PASS: 35 tests; 0 failures, errors or skips (H2/local test infrastructure) |
| PostgreSQL/Redis integration suites | NOT RUN: Docker unavailable |
| Container builds and actual CI run | NOT VERIFIED |
| Deployed multi-user browser, TLS/WSS, accessibility and load tests | NOT RUN |
| Backend dependency/container vulnerability scan | NOT RUN |

The initial sandbox Maven attempt could not access dependencies; the authorized retry ran outside that restriction. The Windows Maven wrapper also failed locally, so the installed Maven executable was used. Frontend npm emitted a sandbox-path warning, but the test/build tools ran successfully. The dependency-audit result is limited to npm production dependencies and does not certify the complete application.

## 7. Release order

Earlier review findings that are now addressed in source: indexed Redis repository selection, supported principal-based session revocation, locking the conversation before allocating message sequences, prompt forward-page catch-up, and frontend lint/format gates. WebSockets, own-password change and administrator reset are also now implemented. These improvements do not remove the need for production-profile release tests.

1. Correct repository packaging and demonstrate a fresh-checkout CI run.
2. Fix and test trusted TLS ingress/client-IP handling.
3. Pass PostgreSQL/Redis and two-instance production-profile tests.
4. Perform a two-browser acceptance test for login, send, history, retry, reconnect, password changes, administrator reset and logout.
5. Establish production account provisioning, secret handling, metric scraping, alert delivery, backups and a successful restore drill.
6. Define load targets, test them and address bottlenecks/quotas.
7. Clearly label or complete local-only features; scope any additional messenger features separately.

**Release decision:** suitable for further development and controlled staging evaluation. Do not describe the whole product as production-ready until the release evidence and deployment gaps above are closed.
