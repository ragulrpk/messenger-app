# Backend Change History

This file records backend changes, their purpose, and their observable functionality. Add new entries at the top using the same format: date, files, change, functionality, and verification.

## 2026-09-23 — Ragul administrator and assisted password recovery

- Added `AppUser.isAdministrator()` to designate canonical `ragul` as the sole administrator; login/session responses expose a navigation-only flag. No migration or account/password mutation on deployment.
- Added `AdminPasswordResetController` and `AdminPasswordResetService` at `POST /api/v1/users/admin-password-reset`. Enforces server-side permissions, enabled account/current session binding, CSRF, administrator-password verification, rate limiting, row locking, password policy, and rejection of self-reset/reuse.
- Successful resets store BCrypt only, invalidate the target's old credential stamps, preserve the administrator session, and log actor/target UUID after commit without credentials. Reused the self-service validation helper and added dated functionality comments.
- Verification: all 35 backend tests passed, including three new administrator recovery tests; log `target/admin-password-reset-tests.log`. Docker-required production tests were not run. Usage and limitations: `../../ADMIN-PASSWORD-RESET.md`.

## 2026-09-23 — Authenticated self-service password changes

- Added `POST /api/v1/users/password`, `PasswordChangeRequest`, `PasswordChangeService`, and safe field-level rejection handling. Requires current-password proof, CSRF, a different new password of at least 12 characters and at most 72 UTF-8 bytes, matching confirmation, and account-scoped rate limiting. Stores BCrypt only and derives the target account from the session.
- Added `AppUser.changePasswordHash` and `UserRepository.lockByUsername`. Login and password changes serialize through the same account lock to prevent stale/concurrent credential updates.
- Added `CredentialStamp` and `CredentialStampFilter`, bound at login and checked for authenticated HTTP/WebSocket access. A changed hash rejects all older sessions across updated instances without depending on Redis cleanup. The current session/cookies are invalidated after commit. Existing pre-feature sessions require re-login after deployment; no schema migration is needed.
- Added dated functionality comments, password-change tests, and real WebSocket coverage for a password changed from another browser. Updated existing security test fixtures to carry real login-equivalent credential bindings.
- Verification: all 32 backend tests passed, zero failures/errors/skips; log `target/password-change-tests.log`. Docker-required production-profile tests remain unexecuted. Usage, impact, and verification limits: `../../PASSWORD-CHANGE.md`.

## 2026-09-23 — Standalone BCrypt password verifier

- Added `tools/PasswordVerifier.java` with a `main` method runnable from an IDE or terminal. It checks a supplied password against a pasted BCrypt hash; BCrypt cannot be decoded to recover the original password.
- Prompts for input, hides the password when a real Java console is available, identifies visible input in IDE consoles, preserves password whitespace, and enforces the application's 72-byte UTF-8 limit. It neither reads nor changes accounts or database data.
- Added dated comments explaining functionality. Verification: compiled with Java 25; smoke checks passed for correct/incorrect passwords, preserved trailing whitespace, rejected oversized passwords, and malformed hashes. Smoke inputs were synthetic; no account credentials were accessed.

## 2026-09-23 — Session-authenticated WebSocket chat updates

- Added `ChatWebSocketConfig` and `ChatWebSocketHandler`: `/api/v1/chat/events`, exact-origin/session checks, read-only participant hints, heartbeats, bounded connections/buffers, and cleanup. Existing sessions are rechecked for logout, expiry, revocation, and disabled accounts.
- Added `ChatChanged`, `ChatEventDelivery`, and `ChatRedisEventsConfig`: changes publish only after database commit; bounded asynchronous delivery keeps transport problems off the HTTP send thread. Local/test delivery stays in-process; production Redis Pub/Sub reaches sockets across instances.
- Updated `ChatService.direct/send` to emit recipient events. Duplicate retries do not emit another event. Conversation locking now precedes sequence allocation on PostgreSQL and H2, correcting the earlier history entry's claim that removing the lock preserved commit-order delivery.
- Replaced the bare session library with Boot 4's `spring-boot-starter-session-data-redis` in `pom.xml`, selected the indexed Redis repository, and explicitly excluded Redis session auto-configuration in local/test profiles. Corrected `SessionRevocationService` to use principal lookup supported by the production registry.
- Added dated comments describing the new functionality and security/recovery decisions. Protocol, impact, deployment, and manual checks are in `../../WEBSOCKET-IMPLEMENTATION.md`.
- Added `ChatWebSocketTests`, `ChatWebSocketHandlerTests`, `ChatEventDeliveryTests`, and Docker-required `ChatWebSocketProductionIT`.

Verification (2026-09-23): the final Maven test run passed all 27 tests with zero failures/errors/skips, including real HTTP/WebSocket upgrades, commit/rollback behavior, participant isolation, logout/revocation, disabled/expired sessions, connection limits, and event failure isolation. Log: `target/websocket-test.log`. Production-profile PostgreSQL/Redis container and HTTPS ingress checks were not run because Docker is unavailable locally; no production-readiness certification or deployment is implied.

## 2026-09-22 — P2 production hardening

- `ChatController.conversations()` validates the cursor with a maximum length of 256 characters. This prevents oversized pagination values from reaching the service and database.
- `RequestCorrelationFilter.doFilterInternal()` trusts an incoming `X-Request-ID` only when the direct peer matches `app.security.trusted-request-id-proxies`. Requests from other clients receive a server-generated ID, protecting log correlation from client-controlled identifiers.
- `application.properties` defines request-header and form/body limits, Tomcat connection/thread/backlog limits, datasource idle/lifetime/keepalive settings, query and transaction timeouts, and the trusted-proxy pattern. Every value remains configurable through environment variables.
- PostgreSQL migration `V4__cover_message_history.sql` creates a descending `(conversation_id, sequence)` history index and includes the returned message columns. This supports efficient history reads and gives PostgreSQL the option of index-only scans.
- `ProductionInfrastructureIT` uses disposable PostgreSQL and Redis containers to validate migrations, startup, concurrent direct-conversation creation, idempotent concurrent sending, history-query planning, and database/cache outage behavior.
- `RequestCorrelationFilterTests` verifies trusted and untrusted request-ID handling.
- `pom.xml` contains the Testcontainers dependencies and an `integration-tests` Failsafe profile. The CI workflow executes this profile after normal verification.

Verification: backend unit/build verification passed with 19 tests; the infrastructure suite passed with 4 tests and Flyway reached version 4.

## 2026-09-22 — P1 production improvements

- `ChatService.conversations()` and its SQL retrieval path fetch conversation summaries and latest messages without the former per-conversation N+1 query pattern.
- Conversation listing uses cursor/keyset pagination based on `updated_at` and `id`, providing stable performance and ordering while conversations change.
- Directory search uses PostgreSQL trigram indexes from `V3__add_directory_search_indexes.sql`, improving contains-style username and display-name searches.
- Message sending avoids using the conversation row as the sequence serialization point while preserving ordered, idempotent messages.
- `LoginOrchestrationService` owns authentication and session establishment. `AuthenticationAuditEvent` and `AuthenticationAuditLogger` record success/failure metadata without credentials.
- `SecurityConfig` defines concurrent-session tracking, and `SessionRevocationService` supports account-wide session invalidation.
- `SecurityErrors` serializes error responses with the configured JSON serializer.
- `ApiExceptionHandler.statusError()` maps known failures to stable public error responses instead of returning arbitrary exception reasons.

## 2026-09-22 — P0 production safeguards

- `ApiExceptionHandler.unexpected()` logs the complete exception with the request ID while avoiding credentials and request bodies.
- Login limiting separates checks, failed-attempt recording, and successful-login clearing. Limits are tracked by normalized username and client address, and `Retry-After` reflects the configured window.
- Redis stores shared production login-attempt counters; local/test profiles use the bounded in-memory implementation.
- Production security no longer exposes Prometheus metrics on the public application endpoint.
- Production image creation uses a verified backend artifact rather than treating a skipped-test package operation as the release gate.
- Datasource and Flyway settings accept separate runtime and migration credentials so production can use least-privilege database roles.

## 2026-09-22 — Docker, Windows PostgreSQL, and health support

- `Dockerfile` produces the Spring Boot runtime image; `Dockerfile.dev` supports development execution.
- Runtime datasource configuration accepts the Windows-host PostgreSQL address and credentials supplied by Compose/environment variables.
- Actuator health, liveness, and readiness endpoints are configured for deployment checks. The local profile also exposes health checking when the backend runs outside Docker.
- `run-local.ps1` and `application-local.properties` support local Spring Boot development without requiring production Redis-backed sessions.
- Flyway migrations create and evolve application users, conversations, memberships, messages, directory-search indexes, and the covering history index.

## Maintenance rule

For every backend change, add an entry containing:

1. The date and scope.
2. The affected class, function, configuration, or migration.
3. What behavior changed and why.
4. The tests or commands used to verify it.
