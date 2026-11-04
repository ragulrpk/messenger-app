# Messenger frontend

React + TypeScript chat UI with Spring Boot cookie/session authentication and persistent direct text messaging.

## Run

Start the backend with `../backend/demo/run-local.ps1`, then run `npm install` and `npm run dev` in this directory. Open `http://localhost:5173`. The Vite proxy sends `/api` to `http://127.0.0.1:8080` and overwrites forwarding headers with the connected client's address.

Use provisioned application accounts; there is no public signup. See the backend README for PostgreSQL setup and local account provisioning. Database credentials never belong in frontend environment variables.

Optional `.env.local` values are documented in `.env.example`. `VITE_AUTH_LOGIN_URL` defaults to `/api/v1/users/login`. `VITE_CHAT_API_URL` defaults to the corresponding `/api/v1/chat` endpoint; set it explicitly if your custom authentication URL uses a different path structure. Production needs an HTTPS reverse proxy for `/api` and an `index.html` fallback for frontend routes.

## Direct messaging

For forgotten passwords, login offers administrator-assisted recovery instructions. The existing `ragul` account can open **Settings → Reset a user’s password**.

- Search the real enabled-user directory by name or username. The signed-in user is excluded. Searches show up to 50 matches; refine the query for additional results.
- Selecting a person creates or restores one server conversation for that pair of users. Both participants share the same history.
- Text messages are stored in PostgreSQL. Refreshing the browser restores conversation history. Local mock data is retained only as test fixtures and is not used in the application.
- A session-authenticated WebSocket at `/api/v1/chat/events` now signals saved changes immediately. The UI retrieves authorized data over HTTP, reconnects automatically, and catches up after disconnects. Five-second list and three-second history polling remain as reconciliation fallback while the tab is visible.
- Conversations and history load in pages of 50. Use **Load more conversations** or **Load older messages**. Incoming-message catch-up follows the last fetched sequence, including when more than one page arrives while disconnected.
- The composer accepts 1–4000 characters after trimming. Enter sends, Shift+Enter inserts a newline, and IME composition does not send. Sending disables the composer until acknowledged. Errors preserve the draft and offer **Retry send**. Retrying unchanged text uses the same client identifier, including after switching conversations, so a lost server response does not duplicate the message.
- Drafts survive switching conversations, mobile Back, and visits to the profile while the chat page remains mounted. Reloading or changing accounts clears unsent drafts and retry identifiers. After an uncertain send and a full reload, check restored history before composing the same message again.
- Up to 5 files (50 MB each) can be attached per send; each file is sent as its own message, with the caption on the first. Groups and delivered/read receipts for the other person are not part of this milestone.
- Pin, mute, manual read/unread markers, and removing a conversation from the list are saved per account and survive reloads and other devices. Unread counts come from the server; opening a conversation in a visible tab marks it read, and marking the open conversation unread closes it. Removing never deletes server messages; searching for the person, or a new message, restores the row.
- Your status and personal note are saved to your account and shown to the people you chat with, next to their name in the list and conversation header. The status is what you chose; it is not set automatically when you sign out.
- Settings offers desktop notifications for new messages from others when the conversation is not on screen. Muted conversations never notify. Notifications only appear while the app is open in a tab.

## Authentication and profile

As of 2026-09-23, Settings → **Change password** opens the form on `/profile#password`. Supply the current password, a different new password (at least 12 characters, at most 72 UTF-8 bytes), and confirmation. Success signs out all older sessions and prompts a fresh login. This is not a forgotten-password recovery flow.

The frontend fetches CSRF tokens for login, logout, conversation creation, and message sending, and includes cookies on all requests. Passwords and session tokens are not persisted in browser storage. Protected routes restore authentication through `/me`; focus, visibility, cross-tab account changes, and unauthorized chat responses recheck the server session. A failed logout keeps the session visible for retry.

Settings includes profile navigation, a local status/note, light/dark theme, sign out, and a separate-tab TN Mail link. Theme persists in this browser and synchronizes across tabs. `/profile` displays supported optional profile fields; missing fields show “Not provided”. The backend currently returns only ID, username, and name.

## Main files

- `src/services/chatService.ts`: cookie/CSRF API requests, timeouts, and message endpoints.
- `src/pages/chat/useMessaging.ts`: server state, polling, pagination, idempotent retries, and message merging.
- `src/pages/chat/ChatPage.tsx`: selection, drafts, and page composition.
- `src/components/chat/`: directory, sidebar, history, composer, and session preferences.
- `src/services/authService.js`, `src/context/`: authentication and session synchronization.

## Verification

Run `npm test`, `npm run build`, and `npm run lint`. Build includes TypeScript checking.

The mounted chat integration test covers directory lookup, a server commit followed by a lost response, duplicate Enter prevention, retry after switching conversations, incoming-message catch-up, older history, reload restoration, and account isolation. Authentication tests cover stale responses and session-expiry events. The backend suite verifies persistence, membership authorization, concurrency, pagination, and CSRF.

For a manual end-to-end check, sign in as two provisioned users in separate browser profiles (or normal and private windows), find the other user, exchange text, and reload both windows. Confirm both histories match. An unrelated third user must neither list nor access that conversation.

### Notifications

Desktop notifications consume individual incoming-message events rather than the latest conversation preview. Chat settings can enable browser push after the backend's VAPID keys are configured. `/notifications-sw.js` handles notification display, deduplication across tabs and clicks; it does not cache application or API responses. It must be served at the origin root over HTTPS (or localhost). Keep the worker outside immutable asset caching. Background delivery requires browser permission and operating-system support.
