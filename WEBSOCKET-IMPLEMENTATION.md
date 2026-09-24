# WebSocket functionality — 2026-09-23

## Behavior and impact

Chat now opens a native WebSocket at `/api/v1/chat/events` after authentication. A committed new conversation or message triggers a participant-only refresh hint. The browser immediately reloads conversation summaries and the selected conversation's newer messages through the existing authorized HTTP APIs.

Sending, validation, CSRF protection, retry IDs, history pagination, and PostgreSQL storage remain HTTP-based. This is live notification followed by HTTP retrieval, not a bidirectional socket message API. No STOMP/SockJS dependency or schema migration was introduced.

```mermaid
sequenceDiagram
    participant A as Sender browser
    participant API as Backend
    participant DB as PostgreSQL
    participant Bus as Redis (production)
    participant B as Recipient browser
    B->>API: WebSocket upgrade + session cookie + Origin
    API-->>B: ready
    A->>API: POST message + CSRF + retry ID
    API->>DB: Lock conversation, insert message, commit
    API-->>A: Saved message
    Note over API,Bus: Async after-commit notification; may race the HTTP response
    API->>Bus: Participant usernames on private event channel
    Bus-->>API: Deliver to every subscribed backend instance
    API-->>B: refresh
    B->>API: GET summaries and messages after last cursor
    API-->>B: Authorized saved data
```

Local/test profiles deliver hints directly to local sockets. Production publishes recipient usernames on `messenger:chat:changed:v1`; each instance forwards hints to its own matching sockets. The bus carries no message bodies. Redis must be private/authenticated as in the production deployment configuration.

## Wire contract

- `ready`: authenticated connection established; reconcile missed changes.
- `refresh`: one of this user's conversations changed; reconcile list and open history.
- `heartbeat`: keep the channel alive, sent on a 20-second scheduled check.
- Client text commands are rejected. Binary data is unsupported. There are no arbitrary subscription destinations.
- Cookies authenticate the upgrade. No password, CSRF token, or session ID is put into the URL.
- Browser origin must exactly match a configured frontend origin; missing/foreign origins are rejected.

The upgrade itself is a read-only GET. Message mutations continue to require CSRF on the REST endpoint. Server recipients are derived from database membership, never from a client-provided destination.

## Session lifecycle and recovery

The server checks account enabled state, session authentication, expiry, and revocation before delivering hints or heartbeats. Invalid sessions are closed with policy code 1008. Idle invalid sockets are detected on the next scheduled heartbeat; scheduling, database, and network delays can extend detection time.

The frontend reconnects with exponential delay, capped at 30 seconds plus jitter. A 45-second watchdog closes silent connections. A policy close triggers HTTP session revalidation and a slower retry. Network-online and visible-tab events trigger reconciliation. Logout, account changes, and component unmount close the old socket and cancel timers.

HTTP reconciliation remains at five seconds for the conversation list and three seconds for selected history while the tab is visible. This protects against a dropped hint, Redis Pub/Sub loss, or a blocked WebSocket proxy. These polling rates were intentionally preserved in this first increment; WebSockets improve delivery latency rather than eliminating background requests.

Forward catch-up drains successive pages immediately, yielding after a bounded batch. Hints arriving during an in-flight HTTP refresh queue another refresh. Message rows are deduplicated by server ID; stable send retry IDs are unchanged.

## Backend changes

- `ChatWebSocketConfig`: endpoint, origin/session handshake checks, scheduler, bounded event executor.
- `ChatWebSocketHandler`: authenticated connections, per-session cap of eight local sockets, bounded output buffer, serialized writes, heartbeat, security rechecks, and cleanup.
- `ChatChanged` / `ChatEventDelivery`: immutable recipient event, after-commit asynchronous delivery, transport-failure isolation.
- `ChatRedisEventsConfig`: production cross-instance Pub/Sub subscriber using a bounded dispatch executor.
- `ChatService`: publishes events for newly created conversations and newly inserted messages; duplicate retries and rollback do not publish a new change.
- `ChatService.send`: acquires the conversation lock before allocating a sequence on both databases. This corrects the earlier PostgreSQL commit-order gap and serializes sends within one conversation.
- `pom.xml` and profile properties: use Boot 4's Redis session starter (the former bare library omitted auto-configuration), explicitly select the indexed repository, and exclude Redis session auto-configuration for local/test servlet sessions.
- `SessionRevocationService`: uses supported principal lookup for the Redis registry rather than unsupported principal enumeration.

## Frontend and proxy changes

- `chatEvents.ts`: native browser connection, secure `wss` URL on HTTPS, backoff, watchdog, visibility/online recovery, and teardown.
- `useMessaging.ts`: live refresh integration, queued invalidations, and multi-page forward catch-up.
- `vite.config.js`: development upgrade proxy with overwritten forwarding headers and preserved browser Origin; explicit Node process import fixes lint.
- `nginx.conf`: dedicated upgrade route with buffering disabled and timeouts longer than the normal heartbeat interval.
- Existing chat layout, message composer, and read-only profile are preserved. No typing, presence, receipts, attachments, or groups are added.

## Deployment and manual checks

1. Restart/rebuild the backend and frontend so the new endpoint and proxy configuration load.
2. Set `FRONTEND_ORIGIN` (or production `PUBLIC_ORIGIN`) to the exact browser origin, including a non-default port. Use HTTPS/WSS in production.
3. Ensure the external ingress permits WebSocket Upgrade and has an idle timeout comfortably above 20 seconds. The provided Nginx route uses 65 seconds.
4. Sign in as two provisioned users in separate browser profiles. In browser developer tools, verify `/api/v1/chat/events` upgrades with status 101 and receives `ready`.
5. Open a conversation and send a message. The other browser should receive `refresh` followed by HTTP history/list requests without waiting for a polling tick.
6. Interrupt and restore connectivity. Verify reconnection and complete missed-message history without duplicate sends.
7. Log out/revoke/disable an account. Verify the old socket closes and private message APIs reject the session.
8. In production staging, connect clients through different backend instances and verify Redis fan-out, Secure cookies, origin restrictions, and the actual TLS ingress chain.

## Verification and limits

Final local verification on 2026-09-23: **27 backend tests and 28 frontend tests passed**. Frontend lint, formatting, TypeScript, and production build passed. The final backend run includes the corrected Spring Boot session dependency and local/test exclusions. Backend output is in `backend/demo/target/websocket-test.log`.

The normal backend tests include real HTTP/WebSocket handshakes, participant isolation, anonymous/foreign/missing-origin rejection, rollback and duplicate retries, logout/revocation/disabled-account closure, and rejected client commands. Separate unit tests check Redis routing and Redis/queue failure isolation.

`ChatWebSocketProductionIT` runs the same transport tests with disposable PostgreSQL and Redis and a non-test profile, exercising the production session registry and Redis event bus. It requires Docker. It does not substitute for a real multiple-instance staging/ingress test.

Commands from `backend/demo`:

```text
mvn test
mvn -Pintegration-tests -Dit.test=ChatWebSocketProductionIT verify
```

On this Windows machine, the installed Maven command additionally needs `-Dmaven.repo.local=<user home>/.m2/repository` and `-DargLine=-Djdk.net.unixdomain.tmpdir=target`. The container test provisions disposable databases; do not point destructive test suites at a real user database.

Frontend checks from `frontend`: `npm test`, `npm run lint`, `npm run format:check`, and `npm run build`.

Frontend tests cover refresh hints without polling, multi-page catch-up, account teardown, reconnect/ready reconciliation, WSS URL selection, and missing WebSocket support. Test doubles verify browser behavior; a deployed browser/ingress test remains required.

Docker is unavailable in this environment, so the production-container suite and Nginx runtime configuration have not been executed here. The application is not being declared fully production-ready. Earlier readiness findings unrelated to this increment, capacity testing, and deployment/restore verification remain outstanding.

## Reference documentation

- [Spring WebSocket handlers, origins, and synchronized sends](https://docs.spring.io/spring-framework/reference/web/websocket/server.html)
- [Spring Data Redis message listener containers](https://docs.spring.io/spring-data/redis/reference/redis/pubsub-receiving.html)
- [Spring Session Redis repository selection](https://docs.spring.io/spring-session/reference/configuration/redis.html)
- [Spring Boot 4 session starter configuration](https://docs.spring.io/spring-boot/reference/web/spring-session.html)
