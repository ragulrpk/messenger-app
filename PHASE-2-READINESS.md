# Phase 2 readiness review

Reviewed: 2026-09-23. Verdict: **not ready for production release**.

Follow-up on 2026-09-23: the WebSocket increment changes several findings below. Indexed Redis configuration, supported revocation lookup, sequence locking, frontend CI gates, and forward catch-up were addressed in source; production-profile container tests were added but cannot run locally without Docker. This file preserves the original review snapshot. See `WEBSOCKET-IMPLEMENTATION.md` and the dated frontend/backend histories for current implementation and verification limits.

Scope is inferred from the repository: secure authentication and persistent one-to-one text messaging. No separate Phase 2 acceptance checklist was supplied. This review does not certify a deployed environment. Application source was not changed.

## Functional coverage

| Area | Implemented behavior | Assessment |
| --- | --- | --- |
| Frontend authentication | Login validation, CSRF exchange, session restoration, protected routes, logout retry, cross-tab session changes | Existing tests pass |
| Frontend messaging | Directory search, direct conversation creation, history, pagination, polling, drafts, stable retry identifiers | Core flow exists; catch-up improvement needed |
| Frontend profile/settings | Profile display, theme persistence, local status/note and sidebar preferences | Preferences other than theme are session-local, not shared backend state |
| Backend authentication | BCrypt, CSRF, session rotation, origin restrictions, IP/account rate limiting, audit events, disabled-account checks | Production session configuration and revocation need correction |
| Backend messaging | Membership authorization, parameterized queries, persistent messages, unique direct pairs, idempotent sends, cursor pagination | Concurrent commit ordering is unsafe for polling |
| Persistence | Flyway, schema validation, foreign keys, PostgreSQL indexes | PostgreSQL-specific behavior needs release tests |
| Operations | Container definitions, health endpoints, metrics, structured logs, backup/restore guidance, CI | Configuration exists; deployment and recovery have not been validated in this review |

Attachments, group chat, read/delivery receipts, shared presence, public signup, recovery, MFA, and editable profiles are not established Phase 2 deliverables. Do not describe local mute/read/status controls as server-backed functionality.

## Release blockers

### 1. Production session repository wiring is incompatible with defaults — high

`backend/demo/src/main/java/com/ctd/demo/config/SecurityConfig.java:79` requires a `FindByIndexNameSessionRepository`. Base application configuration enables Redis but never selects the indexed repository. Spring Session documents the default as `RedisSessionRepository`; select the indexed implementation explicitly and test startup without the local/test profiles. This is a configuration finding, not a reproduced production startup failure.

Reference: https://docs.spring.io/spring-session/reference/configuration/redis.html

### 2. Concurrent PostgreSQL messages can be skipped by polling — high

`backend/demo/src/main/java/com/ctd/demo/chat/ChatService.java:193` inserts before acquiring the conversation update lock. Only the H2 branch locks the conversation before allocating a message sequence. A PostgreSQL transaction can allocate sequence N, pause, and commit after another transaction with N+1. A poll observing N+1 advances its cursor and never fetches N through subsequent `after` requests. The message remains stored but is absent from incremental delivery. The lock taken by the later conversation UPDATE cannot repair sequence allocation order.

Serialize sends per conversation before allocating the sequence, or redesign the cursor around a commit-safe ordering. Add a deterministic PostgreSQL test with two distinct messages, controlled transaction interleaving, and an intervening poll. Existing same-client-ID concurrency coverage does not test this case.

Reference: https://www.postgresql.org/docs/16/functions-sequence.html

### 3. Account-wide revocation is unsupported by the production registry — high

`backend/demo/src/main/java/com/ctd/demo/auth/SessionRevocationService.java:15` calls `getAllPrincipals()`. Inspection of the installed Spring Session 4.1.1 bytecode confirms that `SpringSessionBackedSessionRegistry.getAllPrincipals()` throws `UnsupportedOperationException`. Query sessions for the known username through a supported principal-index lookup instead. Test revocation with Redis and the production registry. Current local-registry tests cannot establish this behavior.

### 4. Required frontend CI gates fail — medium

`npm run lint` fails with `process is not defined` in `frontend/vite.config.js:11` and `:15`. Configure Node globals for build configuration files. `npm run format:check` fails for `frontend/src/pages/chat/useMessaging.ts`. Both gates are required by the checked-in CI workflow.

### 5. Production infrastructure tests bypass production session security — high validation gap

`backend/demo/src/test/java/com/ctd/demo/ProductionInfrastructureIT.java:33` activates `test`. That profile chooses the local session registry and disables Redis session storage. The tests exercise PostgreSQL and a Redis limiter directly, but do not prove production session startup, cross-instance session reuse, concurrent-session limits, or Redis-backed revocation. The test named for readiness does not actually request the readiness endpoint.

Add an isolated production-profile integration suite that logs in over HTTP, reuses the cookie on another instance, revokes sessions, and checks readiness during dependency failure.

## Additional findings

- **Slow catch-up:** `frontend/src/pages/chat/useMessaging.ts:167` uses `hasMore` only for older history. Incoming backlog loads one 50-message page per three-second poll. It eventually catches up when traffic permits, but a large backlog takes many polling cycles. Drain forward pages promptly with cancellation and a bounded work budget.
- **Ingress address handling:** `frontend/nginx.conf:24-25` overwrites forwarded protocol and client address using its direct connection. Behind the documented TLS load balancer this reports HTTP and the load balancer address, potentially grouping all clients into one IP login limit. Configure trusted ingress address/protocol handling and test the actual proxy chain. Preserve protection against client-supplied forwarding headers.
- **Documentation drift:** Backend README still describes offset/nextOffset conversation pagination and says security audit events are deferred. Code uses cursor/nextCursor and publishes authentication audit events. It also claims serialized message writes that the PostgreSQL branch no longer provides.
- **Operational evidence outstanding:** HTTPS browser smoke testing, production-profile container startup, restore drill, load/capacity test, configured alerts, and deployed secret/runtime database-role checks were not performed. Written operational guidance is not evidence that those controls are active.

## Checks performed

| Check | Result |
| --- | --- |
| Frontend Node test suite | 26 passed, 0 failed |
| Frontend TypeScript check and Vite production build | Passed |
| Frontend ESLint | Failed: two Node-global errors |
| Frontend Prettier check | Failed: useMessaging.ts |
| Backend Maven test suite | 19 passed, 0 failed, 0 skipped; H2 test profile |
| PostgreSQL/Redis Testcontainers suite | Not run: Docker executable unavailable in this environment |
| Production containers / deployed browser end-to-end flow | Not verified |
| Dependency vulnerability assessment | Not performed in this review |

Backend run log: `backend/demo/target/readiness-test.log`. Passing H2 and mocked frontend tests does not establish production correctness for PostgreSQL concurrency, Redis sessions, or the ingress chain.

## Release acceptance

1. Correct session repository wiring, Redis revocation, and PostgreSQL send ordering.
2. Make lint, formatting, tests, and build green in CI.
3. Verify production-profile PostgreSQL/Redis startup and real HTTP authentication, messaging, and revocation tests.
4. Verify two users through the HTTPS ingress: login, search, send/reply, refresh, history, disconnected catch-up, uncertain-send retry, expiry, and logout.
5. Demonstrate backup restoration, readiness/alerts, and acceptable latency at the intended user load.

Phase 2 can be considered release-ready only after these checks have evidence. No deployment or production data changes were made during this analysis.
