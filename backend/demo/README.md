# Messenger backend

Spring Boot 4.1.1 / Java 25 / PostgreSQL. Login uses server-side sessions and BCrypt (cost 12). No application password is shipped.

## Run locally

1. Create a PostgreSQL database called `messenger` on 127.0.0.1:5432. Use pgAdmin or `createdb -h localhost -U postgres messenger`.
2. In this directory, run:

```powershell
.\run-local.ps1
```

The explicit `local` profile uses database username `postgres` and password `123`, as requested. Override them using `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. Flyway creates `app_users`; Hibernate validates the schema and does not modify it automatically.

The startup script uses a short project-local JVM socket directory to avoid the Windows Unix-domain socket error observed on this machine. It binds the local HTTP server to 127.0.0.1. For other platforms, use `mvn spring-boot:run -Dspring-boot.run.profiles=local`.

The script explicitly uses the current Windows user's `.m2/repository` cache, avoiding an incorrect Java home-directory resolution to `C:\.m2`. To use a custom Maven cache, pass `-MavenRepository 'D:\path\to\repository'`.

### Run from IntelliJ IDEA

Open `backend/demo` as the Maven project and select the shared **Messenger Local** run configuration. It activates `local` and applies the Windows socket-path workaround. If using an existing `DemoApplication` configuration instead, set program arguments to `--spring.profiles.active=local`, VM options to `-Djdk.net.unixdomain.tmpdir=target`, and the working directory to `backend/demo`.

If startup reports `'url' must start with "jdbc"`, check that `local` is active. Without it, the base configuration requires `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `FRONTEND_ORIGIN`. For local development, remove an invalid `DB_URL` or `SPRING_DATASOURCE_URL` override from the run configuration, or set it to `jdbc:postgresql://127.0.0.1:5432/messenger`. A `postgres://` URL is not a JDBC URL.

The Maven wrapper currently fails on this Windows installation because its generated PowerShell script indexes a null `Target` property. Installed `mvn` works and was used for validation.

## Create your first local account

Before starting, set `BOOTSTRAP_USERNAME` and `BOOTSTRAP_PASSWORD` in the terminal. Choose a username containing 3–64 lowercase letters, digits, dots, underscores, or hyphens, and a password of at least 12 characters, at most 72 UTF-8 bytes. For example:

```powershell
$env:BOOTSTRAP_USERNAME = 'ragul'
# Prompt instead of recording the application password in shell history.
$env:BOOTSTRAP_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'New application password' -AsSecureString)).Password
.\run-local.ps1
```

Remove these environment variables after stopping the process. Provisioning is enabled only under `local`, inserts only a missing account, hashes its password, and never resets an existing account. Database credentials are separate from application login credentials. There is no public registration endpoint.

## Controller flow

As of 2026-09-23, authenticated `POST /api/v1/users/password` accepts `{ currentPassword, newPassword, confirmPassword }` with CSRF protection. It verifies and changes only the session owner's password, returns 204, and makes all older credential-bound sessions invalid. Deploying this feature requires existing users to sign in again; no schema migration is needed.

`AuthController → AuthService → AuthenticationManager → UserDetailsService → UserRepository → PostgreSQL`

1. `GET /api/v1/users/csrf` returns `{ "headerName": "X-CSRF-TOKEN", "token": "..." }` and establishes an anonymous session.
2. `POST /api/v1/users/login` sends JSON `{ "username": "ragul", "password": "..." }`, the session cookie, and the CSRF header.
3. The service normalizes the username, validates input, and verifies the stored password hash. It rejects disabled accounts, and never returns the password hash.
4. Successful login rotates the session identifier and CSRF token, explicitly persists Spring Security's context, and returns `{ "user": { "id": "UUID", "username": "ragul", "name": "ragul" } }`.
5. `GET /api/v1/users/me` restores the current user. Anonymous requests receive 401.
6. Fetch a fresh CSRF token, then `POST /api/v1/users/logout` with that token. Logout returns 204, invalidates the session, and deletes its cookie.

Validation failures return 400; invalid credentials return a generic 401; invalid CSRF and denied origins return 403; repeated login requests return 429. Cookies are HttpOnly, SameSite=Lax, and Secure outside the local profile. Sessions expire after 30 minutes of inactivity. Authentication responses are not cacheable.

React's auth service performs the token exchange automatically, restores the session on page load, and invalidates it on sign-out. Use `http://localhost:5173` or `http://127.0.0.1:5173` for Vite, matching the local CORS allowlist. Vite forwards `/api` requests to `http://127.0.0.1:8080`. Set `FRONTEND_ORIGIN` if your port changes. Use the same hostname consistently for both frontend and backend.

## Production deployment requirements

Run without the `local` profile. Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_URL`, and `FRONTEND_ORIGIN` through deployment secrets/configuration. Use a dedicated database role, not the local postgres superuser. Apply migrations with a migration role and use a least-privilege runtime role for application tables in managed deployments.

Terminate HTTPS at your trusted ingress; Secure cookies require HTTPS. Prefer serving the frontend and API on the same site. The current cookie policy intentionally does not support unrelated cross-site domains. Do not disable CSRF to work around integration errors.

The production rate limiter stores per-address counters in Redis, so limits apply across backend instances. Spring Session stores authenticated sessions in the same Redis service, allowing requests to move safely between instances. The local and test profiles use bounded in-memory rate limiting and servlet sessions, so local development does not require Redis. Tomcat resolves `X-Forwarded-For` only when the connecting peer matches `TRUSTED_PROXY_PATTERN`, a Java regular expression matching your ingress IP addresses (for example, `10[.]0[.]0[.]10`). Production defaults to trusting no proxies. Set the pattern to the ingress addresses, make the ingress overwrite client-supplied forwarding headers, and restrict direct backend access to that ingress.

Provision production users through an approved administrative process with BCrypt hashes. The canonical `ragul` account is the sole administrator and can reset other users' passwords after reauthentication and identity verification. Public signup, email recovery, MFA, and a dedicated audit-history interface remain later product phases. Successful administrator resets are recorded in application logs.

## Tests

```powershell
mvn test
```

On Windows installations affected by the JVM Unix-domain socket path issue, use `mvn test '-DargLine=-Djdk.net.unixdomain.tmpdir=target'` so the HTTP integration tests use the same short socket directory as local startup.

Integration tests use an isolated H2 database in PostgreSQL compatibility mode. Flyway migrations run during tests. Tests cover persisted login, session rotation, token rotation, logout, invalid credentials, disabled accounts, malformed input, CORS, and password hashing. Rate-limit tests include real HTTP requests through Tomcat to verify that trusted proxies preserve separate client limits and untrusted forwarded headers cannot bypass limits. These tests never clear the local PostgreSQL database.

Security implementation references: [session persistence](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) and [CSRF protection](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).



## Persistent direct messaging

### Load local sample conversations

After starting the backend and provisioning your account, run `scripts/seed-local-chat.sql` against the **local** `messenger` database using psql with `-v owner=ragul` (replace `ragul` with the existing account username). The script adds Priya, Alex, and Indhuja sample contacts and eight messages per conversation. It is safe to rerun: existing accounts/passwords are preserved, and messages are not duplicated. These sample contacts have no published login credentials. They are labeled `(Sample)` and are intended for UI development, not production.

On sign-in, the frontend opens the most recent conversation and loads its saved message history automatically. The message composer stays empty until you type a new message.

Flyway migration `V2__create_messaging.sql` adds `chat_conversations`, `chat_members`, and `chat_messages`. Restart the backend to apply it. The migration preserves existing users. Direct pairs are unique; membership and message sender relationships are enforced by foreign keys. Each conversation serializes message writes so sequence cursors follow commit order. Reusing a client message identifier with unchanged text returns the existing message; reusing it for different text returns 409.

All chat routes require an authenticated, enabled account. The backend obtains the sender from the session, never from the request body. Message reads and writes verify membership; unknown and inaccessible conversations both return 404. POST requests require a fresh valid CSRF token obtained from `/api/v1/users/csrf`.

| Method | Route | Body/query | Result |
| --- | --- | --- | --- |
| GET | `/api/v1/chat/users` | `q` (max 100 characters) | Up to 50 enabled people, excluding self; only ID, username, name |
| GET | `/api/v1/chat/conversations` | Optional `cursor` | `{ conversations, nextCursor }`, 50 per page, newest activity first |
| POST | `/api/v1/chat/conversations/direct` | `{ userId }` | Existing or newly created direct conversation |
| GET | `/api/v1/chat/conversations/{id}/messages` | Optional `before` OR `after` sequence | `{ messages, hasMore }`, 50 per page in ascending order |
| POST | `/api/v1/chat/conversations/{id}/messages` | `{ clientId, text }` | Saved message, including server ID, sequence, sender, timestamp |
| POST | `/api/v1/chat/conversations/{id}/preferences` | Any of `{ pinned, muted, hidden, unread }` | Updated conversation summary; omitted fields are unchanged |
| POST | `/api/v1/chat/conversations/{id}/read` | `{ sequence }` (optional) | Updated summary; read position moves forward only, all messages when omitted |
| GET | `/api/v1/chat/presence` | — | `{ status, note }` of the caller |
| POST | `/api/v1/chat/presence` | `{ status, note }` | Status is `online`, `offline`, `meeting` or `busy`; note at most 160 characters |

`userId`, conversation IDs, and `clientId` are UUIDs. Text must be nonblank and at most 4000 characters. Omit message cursors for the latest page; `before` loads older history and `after` catches up with incoming messages. Continue catch-up when `hasMore` is true. As of 2026-09-23, `/api/v1/chat/events` provides authenticated WebSocket refresh hints after commit, with Redis Pub/Sub across production instances. Message sending and retrieval remain HTTP-based; polling provides reconciliation fallback.

Conversation summaries contain `{ id, targetId, name, updatedAt, lastMessage, pinned, muted, hidden, unread, targetStatus, targetNote }`. Preferences and `unread` belong to the caller only. `unread` counts the other person's messages after the caller's read position, and is at least 1 when manually marked unread. Message objects contain `{ id, sequence, senderId, senderName, clientId, text, sentAt, attachment }`. `lastMessage` is null for a newly created empty conversation.

Migration `V7__member_preferences_and_presence.sql` adds the preference and read-position columns to `chat_members` (existing history starts as read) and a `user_presence` table. A new message un-hides the conversation for every member; sending also marks the sender's own read position. Preference, read and presence changes send refresh hints to the caller's other tabs, and presence changes also reach everyone who shares a conversation with the caller.

Groups and read receipts shown to the other person are not implemented. Provision a second local account by starting with a different `BOOTSTRAP_USERNAME` and password; provisioning never overwrites an existing account. Use separate browser profiles when testing two accounts because tabs normally share the same session cookie.

### Messaging verification

Run the complete test suite with the installed Maven CLI. On this Windows installation:

```powershell
mvn.cmd "-Dmaven.repo.local=$env:USERPROFILE/.m2/repository" '-DargLine=-Djdk.net.unixdomain.tmpdir=target' test
```

The default test profile uses H2. The suite also supports PostgreSQL by overriding `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and `SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver` for the test process. **Use a dedicated disposable database only: tests delete users and messaging records.** Both migrations and the full suite were verified against an isolated PostgreSQL database during implementation, then that database was removed.

## Message notifications and browser push

Notifications now use an ordered database feed (`GET /api/v1/chat/notifications?after=…`), independent of conversation previews and sidebar pagination. A first request without a cursor establishes a baseline; subsequent requests page through individual incoming messages. Muted chats stay quiet except for mentions. Self-messages, cleared/hidden/deleted messages and conversations the recipient left are excluded. Feed entries expire after seven days. Browser tabs share an account-scoped cursor; the notification service worker deduplicates foreground and background delivery by message ID.

For notifications while the app is closed, serve the app over HTTPS (localhost is suitable for development). Generate a stable VAPID pair once with `java scripts/GeneratePushKeys.java`. Set `PUSH_PUBLIC_KEY`, `PUSH_PRIVATE_KEY` and `PUSH_SUBJECT` (`mailto:` administrator contact) on every backend replica, using the same pair across replicas. Store the private key in deployment secrets, never in the frontend. Compose forwards these variables. In Kubernetes, add them to the backend Secret/environment. Without the keys, live desktop notifications still work and settings explicitly reports that background notifications are unavailable.

Users enable notifications in Chat settings and grant browser permission. Existing push subscriptions are refreshed on sign-in. The push worker stores progress in PostgreSQL, retries temporary delivery failures, and removes expired subscriptions (HTTP 404/410). Logout removes that session's subscriptions; password changes and disabled accounts invalidate them. Notifications do not extend HTTP sessions. Clicking an alert opens its conversation; normal authentication is still required to access messages. Supported endpoints are the standard Chrome/Edge, Firefox, Apple and Windows browser push services. Delivery depends on the browser/OS allowing background push; a browser or device forcibly stopped by its user may not receive it until restarted.

### Change synchronization and concurrent actions

`GET /api/v1/chat/conversations/{id}/messages` uses independent message-sequence and change-revision cursors. Pass `changedAfter` to recover edits, reactions, deletions and per-user removals. The response `revision` is the fully processed continuation position, not necessarily the conversation's newest revision. While `hasMoreChanges` is true, request another page with `changedAfter=revision`, even when there are no newer messages or the change page is empty. Each response covers at most 500 mutation revisions; both changed messages and removals are bounded by that same interval. Changes made while paging are recovered by later intervals. The frontend applies every page before advancing its cursor, retries failures from the last applied position and yields between long catch-up batches.

Message edits, deletes and reactions locate the conversation, acquire its database row lock, then reload current membership, visibility, ownership and deletion state before applying the action. Sends recheck membership and quoted-message access under that lock too. A mutation waiting behind an unsend, history clear or membership removal cannot use its earlier authorization result. Concurrent edits are serialized with the last successful edit taking effect; there is no optimistic version conflict prompt.
