import { after, test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "vite";
import { JSDOM } from "jsdom";
import { act, createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
const server = await createServer({
  server: { middlewareMode: true, hmr: false, ws: false },
  appType: "custom",
});
after(() => server.close());
const { searchDirectory, sections, applyAction, splitMentions, mentions } =
  await server.ssrLoadModule("/src/components/chat/chatState.ts");
const { people, groups, mockConversations } = await server.ssrLoadModule(
  "/src/data/mockChatData.ts",
);
test("unread groups show only the latest message with its sender", async () => {
  const { default: ConversationItem } = await server.ssrLoadModule(
    "/src/components/chat/ConversationItem.tsx",
  );
  const conversation = {
    id: "group-preview",
    type: "GROUP",
    name: "work",
    updatedAt: "2026-10-30T10:00:00Z",
    unread: 2,
    messages: [],
    unreadPreviews: [
      { id: "one", senderName: "Ragul", text: "hi", attachmentName: null },
      { id: "two", senderName: "Indhuja", text: "hello", attachmentName: null },
    ],
    lastMessage: { id: "two", senderName: "Indhuja", text: "hello" },
  };
  const render = (value) =>
    renderToStaticMarkup(
      createElement(ConversationItem, {
        conversation: value,
        selectedId: null,
        onSelect() {},
        onAction() {},
      }),
    );
  const html = render(conversation);
  assert.match(html, /has-unread/);
  assert.doesNotMatch(html, /Ragul: hi/);
  assert.match(html, /Indhuja: /);
  assert.match(html, /hello/);
  const read = render({ ...conversation, unread: 0 });
  assert.doesNotMatch(read, /Ragul: hi/);
  assert.match(read, /Indhuja: /);
});

test("previews drop messages deleted for the user, even ones another device removed", async () => {
  const { keepLocalPreview, previewAfter } = await server.ssrLoadModule(
    "/src/pages/chat/useMessaging.ts",
  );
  const message = (id, sequence, sentAt) => ({
    id,
    sequence,
    sentAt,
    senderId: "alice",
    senderName: "Alice",
    text: id,
  });
  const nine = message("nine", 9, "2026-11-02T10:00:09Z");
  const ten = message("ten", 10, "2026-11-02T10:00:10Z");
  // A slower list response, read before the send of 10 committed, keeps the acknowledged send.
  const stale = { lastMessage: nine, updatedAt: nine.sentAt };
  assert.equal(keepLocalPreview(ten, stale), true);
  // The server has seen 10 (updatedAt covers it) but previews 9: 10 is hidden from
  // this user now, so the server's preview wins.
  const hidden = { lastMessage: nine, updatedAt: ten.sentAt };
  assert.equal(keepLocalPreview(ten, hidden), false);
  assert.equal(keepLocalPreview(nine, hidden), false);
  assert.equal(keepLocalPreview(null, hidden), false);
  // Deleting the previewed message here shows the newest remaining loaded message.
  assert.equal(previewAfter(ten, [nine], null, "ten"), nine);
  assert.equal(previewAfter(ten, [], null, "ten"), null);
  assert.equal(previewAfter(ten, [nine, ten], null, "nine"), ten);
  const edited = { ...ten, text: "edited" };
  assert.equal(previewAfter(ten, [nine, edited], edited), edited);
  assert.equal(previewAfter(ten, [nine, ten], { ...nine, text: "x" }), ten);
});

test("delivery is reported only for unreported arrivals from others", async () => {
  const { newArrivals } = await server.ssrLoadModule(
    "/src/pages/chat/useMessaging.ts",
  );
  const row = (id, unread, sequence) => ({
    id,
    unread,
    lastMessage: sequence ? { id: `m${sequence}`, sequence } : null,
  });
  const rows = [row("a", 2, 7), row("b", 0, 9), row("c", 1, 4)];
  // Unread rows hold messages from others; "b" ends with the user's own message.
  assert.deepEqual(
    newArrivals(rows, {}).map((r) => r.id),
    ["a", "c"],
  );
  // Already reported up to those messages: the poll reports nothing.
  assert.deepEqual(newArrivals(rows, { a: 7, c: 4 }), []);
  // A newer arrival in "a" is reported again.
  assert.deepEqual(
    newArrivals([row("a", 3, 8)], { a: 7 }).map((r) => r.id),
    ["a"],
  );
});

test("search trims spaces, ignores case, and searches people and groups", () => {
  assert.equal(
    searchDirectory("  INDHU  ", people, groups).people[0].name,
    "Indhuja",
  );
  assert.equal(
    searchDirectory(" TEAM ", people, groups).groups[0].name,
    "Development Team",
  );
  assert.deepEqual(searchDirectory("   ", people, groups), {
    people: [],
    groups: [],
  });
  assert.deepEqual(searchDirectory("no-such-person", people, groups), {
    people: [],
    groups: [],
  });
});
test("pinned items are unique and excluded from chronologically sorted recent items", () => {
  const { pinned, recent } = sections(
    [...mockConversations].reverse().concat(mockConversations[0]),
  );
  assert.equal(pinned.length, 2);
  assert.equal(recent.length, 2);
  assert.equal(recent[0].name, "Alex Morgan");
  assert.ok(recent.every((c) => !pinned.some((p) => p.id === c.id)));
});
test("actions target only the intended conversation without mutating mock data", () => {
  const id = mockConversations[0].id;
  let changed = applyAction(mockConversations, id, "pin");
  assert.equal(sections(changed).pinned.length, 1);
  changed = applyAction(changed, id, "read");
  assert.equal(changed[0].unread, 0);
  changed = applyAction(changed, id, "read");
  assert.equal(changed[0].unread, 1);
  changed = applyAction(changed, id, "mute");
  assert.equal(changed[0].muted, true);
  changed = applyAction(changed, id, "remove");
  assert.ok(!sections(changed).recent.some((c) => c.id === id));
  assert.equal(mockConversations[0].hidden, false);
  assert.equal(changed[1], mockConversations[1]);
});
test("mentions are unique usernames, shown as names", () => {
  const people = [
    { id: "1", username: "indhuja.r", name: "Indhuja" },
    { id: "2", username: "indhuja.k", name: "Indhuja" },
  ];
  assert.deepEqual(
    splitMentions("Hi @indhuja.k. Mail ravi@tn.gov.in", people),
    [
      { text: "Hi ", mention: null },
      { text: "@Indhuja", mention: people[1] },
      { text: ". Mail ravi@tn.gov.in", mention: null },
    ],
    "the same name still points at one person; an email is not a mention",
  );
  assert.equal(
    mentions("ping @indhuja.kk", "indhuja.k"),
    false,
    "a longer username is someone else",
  );
  assert.equal(mentions("thanks @Indhuja.K!", "indhuja.k"), true);
  assert.equal(mentions("no mention", undefined), false);
});
test("empty lists produce both empty sections", () => {
  assert.deepEqual(sections([]), { pinned: [], recent: [] });
});

test("persistent chat: directory, failed send, safe retry, catch-up, pagination and account isolation", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  // jsdom never has window focus; the test decides whether the user is looking.
  let focused = true;
  dom.window.document.hasFocus = () => focused;
  dom.window.HTMLElement.prototype.scrollIntoView = () => {};
  dom.window.HTMLElement.prototype.getBoundingClientRect = () => ({
    top: 0,
    bottom: 100,
    height: 100,
    left: 0,
    right: 100,
    width: 100,
  });
  dom.window.requestAnimationFrame = (callback) => callback();
  // 2026-09-23: Simulate server hints to prove UI refreshes without waiting for a polling tick.
  const liveSockets = [];
  class LiveSocket {
    static CLOSED = 3;
    readyState = 1;
    constructor() {
      liveSockets.push(this);
    }
    close() {
      this.readyState = 3;
    }
    receive(data) {
      this.onmessage?.({ data });
    }
  }
  window.WebSocket = LiveSocket;
  const originalFetch = globalThis.fetch;
  const intervals = new Map();
  const originalTimeout = window.setTimeout.bind(window);
  let expireTyping;
  let expireClearToast;
  window.setTimeout = (callback, delay, ...args) => {
    if (delay === 5000) expireTyping = callback;
    if (delay === 3000) expireClearToast = callback;
    return originalTimeout(callback, delay, ...args);
  };
  window.setInterval = (callback, delay) => {
    const key = Symbol();
    intervals.set(key, { delay, callback });
    return key;
  };
  window.clearInterval = (key) => intervals.delete(key);
  let actor = "alice";
  let opened = false;
  let loseResponse = true;
  let failHistory = false;
  let failClear = false;
  let sendCount = 0;
  let activityReports = 0;
  const sentIds = [];
  const typingRequests = [];
  const uploads = [];
  const preferenceRequests = [];
  const readRequests = [];
  const presenceRequests = [];
  let saved = { pinned: false, muted: false, hidden: false, unread: 0 };
  let messages = [];
  const conversation = () => ({
    id: "12345678-1234-1234-1234-123456789abc",
    targetId: "bob",
    name: "Bob",
    updatedAt: messages.at(-1)?.sentAt ?? "2026-09-11T00:00:00Z",
    lastMessage:
      (messages.at(-1)?.sequence ?? 0) > (saved.historyFromSequence ?? 0)
        ? messages.at(-1)
        : null,
    ...saved,
    targetStatus: "meeting",
    targetNote: "Back at 3",
    ...receipts,
  });
  let receipts = { targetDeliveredSequence: 0, targetReadSequence: 0 };
  // Message actions bump the conversation's change counter, as the server does.
  let revision = 0;
  let failChangeAfter = null;
  const changeRequests = [];
  const actionRequests = [];
  let lastSendBody;
  // Groups the mock server knows, and the group requests it received.
  let groups = [];
  let groupMembers = [];
  const groupRequests = [];
  const groupArrivals = [];
  const notificationItems = new Map();
  let notificationSerial = 0;
  const response = (value, status = 200) =>
    new Response(JSON.stringify(value), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  globalThis.fetch = async (url, options = {}) => {
    assert.equal(options.credentials, "include");
    const route = new URL(url, "http://localhost");
    if (route.pathname.endsWith("/notifications")) {
      for (const [message, row] of [
        ...messages.map((m) => [m, conversation()]),
        ...groupArrivals,
      ]) {
        if (message.senderId === "alice" || notificationItems.has(message.id))
          continue;
        notificationItems.set(message.id, {
          message,
          row,
          position: ++notificationSerial,
        });
      }
      const after = route.searchParams.get("after");
      if (after === null)
        return response({
          notifications: [],
          cursor: notificationSerial,
          hasMore: false,
        });
      const found = [...notificationItems.values()].filter(
        ({ message, row, position }) =>
          actor === "alice" &&
          position > Number(after) &&
          message.sequence > (saved.historyFromSequence ?? 0) &&
          (!(row.type === "GROUP"
            ? groups.find((g) => g.id === row.id)?.muted
            : saved.muted) ||
            message.text.includes("@alice")),
      );
      return response({
        notifications: found.map(({ message, row, position }) => ({
          position,
          userId: "alice",
          conversationId: row.id,
          messageId: message.id,
          messageSequence: message.sequence,
          mentioned: message.text.includes("@alice"),
          title: message.text.includes("@alice")
            ? `You have been mentioned in ${row.name}`
            : row.name,
          body:
            row.type === "GROUP"
              ? `${message.senderName}: ${message.text}`
              : message.text,
        })),
        cursor: notificationSerial,
        hasMore: false,
      });
    }
    if (route.pathname.endsWith("/csrf"))
      return response({ headerName: "X-CSRF-TOKEN", token: "token" });
    if (route.pathname.endsWith("/users/lookup")) {
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      const { usernames } = JSON.parse(options.body);
      return response(
        usernames
          .filter((name) => name === "bob")
          .map(() => ({ id: "bob", username: "bob", name: "Bob" })),
      );
    }
    if (route.pathname.endsWith("/users")) {
      assert.equal(route.searchParams.get("q").trim().toLowerCase(), "bob");
      return response([{ id: "bob", username: "bob", name: "Bob" }]);
    }
    if (route.pathname.endsWith("/conversations/search"))
      return response(
        groups.filter((g) =>
          g.name
            .toLowerCase()
            .includes(route.searchParams.get("q").trim().toLowerCase()),
        ),
      );
    if (route.pathname.endsWith("/messages/search"))
      return response({
        messages: messages.filter((m) =>
          m.text
            .toLowerCase()
            .includes(route.searchParams.get("q").toLowerCase()),
        ),
        hasMore: false,
      });
    if (route.pathname.endsWith("/clear")) {
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      if (failClear) return response({}, 503);
      saved = {
        ...saved,
        unread: 0,
        historyFromSequence: messages.at(-1)?.sequence ?? 0,
      };
      return response(conversation());
    }
    if (options.method !== "POST" && route.pathname.endsWith("/files"))
      return response({
        messages: messages
          .filter(
            (m) =>
              m.attachment &&
              !m.deleted &&
              m.sequence > (saved.historyFromSequence ?? 0),
          )
          .reverse(),
        hasMore: false,
      });
    const groupPath = /\/conversations\/(.+)$/.exec(route.pathname)?.[1];
    if (
      groupPath === "group" ||
      /\/(members|rename|leave)(\/|$)/.test(groupPath ?? "")
    ) {
      const body = options.body ? JSON.parse(options.body) : undefined;
      groupRequests.push({ path: groupPath, body });
      if (groupPath === "group") {
        const group = {
          id: "aaaaaaaa-0000-0000-0000-000000000001",
          targetId: null,
          name: body.name,
          updatedAt: "2026-09-12T00:00:00Z",
          lastMessage: null,
          pinned: false,
          muted: false,
          hidden: false,
          unread: 0,
          targetStatus: null,
          targetNote: null,
          targetDeliveredSequence: 0,
          targetReadSequence: 0,
          type: "GROUP",
          role: "admin",
          memberCount: 1 + body.memberIds.length,
        };
        groups.push(group);
        groupMembers = [
          { id: "alice", username: "alice", name: "Alice", role: "admin" },
          ...body.memberIds.map((id) => ({
            id,
            username: id,
            name: "Bob",
            role: "member",
          })),
        ];
        return response(group);
      }
      const id = groupPath.split("/")[0];
      if (groupPath.endsWith("/leave")) {
        groups = groups.filter((g) => g.id !== id);
        return response({ left: true });
      }
      if (groupPath.endsWith("/remove")) {
        const removed = groupPath.split("/")[2];
        groupMembers = groupMembers.filter((m) => m.id !== removed);
        groups = groups.map((g) =>
          g.id === id ? { ...g, memberCount: groupMembers.length } : g,
        );
      }
      return response(groupMembers);
    }
    if (route.pathname.endsWith("/direct")) {
      assert.deepEqual(JSON.parse(options.body), { userId: "bob" });
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      opened = true;
      return response(conversation());
    }
    if (route.pathname.endsWith("/conversations"))
      return response({
        conversations:
          opened && actor === "alice" ? [conversation(), ...groups] : [],
        nextCursor: null,
      });
    if (route.pathname.endsWith("/presence")) {
      if (options.method !== "POST")
        return response({ status: "busy", note: "Saved note" });
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      presenceRequests.push(JSON.parse(options.body));
      return response(JSON.parse(options.body));
    }
    if (route.pathname.endsWith("/preferences")) {
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      const body = JSON.parse(options.body);
      preferenceRequests.push(body);
      const { unread, ...flags } = body;
      saved = { ...saved, ...flags, ...(unread ? { unread: 1 } : {}) };
      return response(conversation());
    }
    if (route.pathname.endsWith("/read")) {
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      readRequests.push(JSON.parse(options.body));
      saved = { ...saved, unread: 0 };
      return response(conversation());
    }
    if (route.pathname.endsWith("/typing")) {
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      typingRequests.push(JSON.parse(options.body));
      return response({ accepted: true });
    }
    if (route.pathname.endsWith("/delivered")) {
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      return response({ delivered: true });
    }
    // The inactivity guard reports real use of the app.
    if (route.pathname.endsWith("/users/activity")) {
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      activityReports++;
      return new Response(null, { status: 204 });
    }
    if (route.pathname.endsWith("/files")) {
      assert.ok(options.body instanceof FormData, "uploads are multipart");
      assert.equal(options.headers["Content-Type"], undefined);
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      const files = options.body.getAll("file");
      uploads.push({
        clientId: options.body.get("clientId"),
        text: options.body.get("text"),
        name: files[0].name,
        names: files.map((f) => f.name),
      });
      const attachments = files.map((file, index) => ({
        id: `file-${uploads.length}${index ? `-${index}` : ""}`,
        name: file.name,
        contentType: "application/pdf",
        size: file.size,
        available: true,
      }));
      const message = {
        id: `message-${messages.length + 1}`,
        sequence: messages.length + 1,
        clientId: options.body.get("clientId"),
        text: options.body.get("text"),
        senderId: "alice",
        senderName: "Alice",
        sentAt: "2026-09-11T12:00:00Z",
        attachment: attachments[0],
        attachments,
      };
      messages.push(message);
      return response(message);
    }
    const messageAction =
      /\/messages\/([^/]+)\/(edit|delete|reaction|forward)$/.exec(
        route.pathname,
      );
    if (messageAction) {
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      const [, messageId, verb] = messageAction;
      const body = JSON.parse(options.body);
      actionRequests.push({ messageId, verb, body });
      const message = messages.find((m) => m.id === messageId);
      message.revision = ++revision;
      if (verb === "edit")
        Object.assign(message, { text: body.text, edited: true });
      if (verb === "reaction")
        message.reactions = body.emoji
          ? [{ emoji: body.emoji, count: 1, mine: true }]
          : [];
      if (verb === "delete") {
        if (body.everyone)
          Object.assign(message, { deleted: true, text: "", attachment: null });
        return response({ deleted: true });
      }
      if (verb === "forward")
        return response({ ...message, id: "forwarded", forwarded: true });
      return response(message);
    }
    if (options.method === "POST") {
      const body = JSON.parse(options.body);
      lastSendBody = body;
      sendCount++;
      sentIds.push(body.clientId);
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      let message = messages.find((m) => m.clientId === body.clientId);
      if (!message) {
        message = {
          id: `message-${messages.length + 1}`,
          sequence: messages.length + 1,
          clientId: body.clientId,
          text: body.text,
          senderId: "alice",
          senderName: "Alice",
          sentAt: "2026-09-11T10:00:00Z",
        };
        messages.push(message);
      }
      if (loseResponse) {
        loseResponse = false;
        throw new TypeError("response lost after commit");
      }
      return response(message);
    }
    if (failHistory) return response({}, 503);
    const before = route.searchParams.get("before");
    const after = route.searchParams.get("after");
    const matching = messages.filter(
      (m) =>
        m.sequence > (saved.historyFromSequence ?? 0) &&
        (!before || m.sequence < Number(before)) &&
        (after === null || m.sequence > Number(after)),
    );
    const page = after === null ? matching.slice(-50) : matching.slice(0, 50);
    const changedAfter = route.searchParams.get("changedAfter");
    if (changedAfter !== null) changeRequests.push(Number(changedAfter));
    if (changedAfter !== null && Number(changedAfter) === failChangeAfter) {
      failChangeAfter = null;
      return response({}, 503);
    }
    const through =
      changedAfter === null
        ? revision
        : Math.min(revision, Number(changedAfter) + 500);
    return response({
      messages: page,
      hasMore: matching.length > 50,
      revision: through,
      hasMoreChanges: through < revision,
      changed:
        changedAfter === null
          ? []
          : messages.filter(
              (m) =>
                (m.revision ?? 0) > Number(changedAfter) &&
                (m.revision ?? 0) <= through,
            ),
      removed: [],
    });
  };
  const { createRoot } = await import("react-dom/client");
  const { default: App } = await server.ssrLoadModule("/src/App.jsx");
  const { AuthContext } = await server.ssrLoadModule("/src/context/useAuth.js");
  const root = createRoot(document.getElementById("root"));
  const click = async (selector) => {
    const element =
      typeof selector === "string"
        ? document.querySelector(selector)
        : selector;
    assert.ok(element, `Missing ${selector}`);
    await act(async () => element.click());
  };
  const input = async (element, value) => {
    const prototype =
      element.tagName === "TEXTAREA"
        ? dom.window.HTMLTextAreaElement.prototype
        : dom.window.HTMLInputElement.prototype;
    await act(async () => {
      Object.getOwnPropertyDescriptor(prototype, "value").set.call(
        element,
        value,
      );
      element.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    });
  };
  const renderUser = async (id, key = "app") => {
    actor = id;
    await act(async () =>
      root.render(
        createElement(
          AuthContext.Provider,
          {
            value: {
              user: { id, name: id, username: id },
              initializing: false,
              signOut() {},
              signingOut: false,
              logoutError: "",
            },
          },
          createElement(
            MemoryRouter,
            { initialEntries: ["/chat"], key },
            createElement(App),
          ),
        ),
      ),
    );
  };
  const tick = async (delay) => {
    await act(async () => {
      for (const timer of [...intervals.values()])
        if (timer.delay === delay) timer.callback();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
  };
  try {
    await renderUser("alice");
    assert.equal(
      document.querySelectorAll(".conversation-select").length,
      0,
      "no mock conversations",
    );
    // Opening the app counts as use: the inactivity clock starts on the server.
    assert.ok(activityReports >= 1, "activity reported on load");
    await input(document.getElementById("people-search"), "  BOB  ");
    assert.match(document.body.textContent, /Searching/);
    await act(async () => new Promise((resolve) => setTimeout(resolve, 240)));
    await click(".search-result");
    assert.equal(document.querySelector("h1").textContent, "Bob");
    assert.match(document.body.textContent, /beginning of your conversation/);
    assert.equal(
      document.querySelector('[aria-label="Attach a file"]').disabled,
      false,
    );
    const draft = document.getElementById("chat-draft");
    await input(draft, "Hello Bob");
    assert.deepEqual(typingRequests.at(-1), { active: true });
    await input(draft, "Hello Bob!");
    await input(draft, "Hello Bob");
    assert.equal(typingRequests.length, 1, "keystrokes are throttled");
    await act(async () =>
      liveSockets.at(-1).receive(`typing:${conversation().id}:true`),
    );
    assert.match(document.body.textContent, /Bob is typing/);
    await act(async () =>
      liveSockets
        .at(-1)
        .receive("typing:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:false"),
    );
    assert.match(
      document.body.textContent,
      /Bob is typing/,
      "other conversations cannot clear the indicator",
    );
    await act(async () =>
      liveSockets.at(-1).receive(`typing:${conversation().id}:false`),
    );
    assert.doesNotMatch(document.body.textContent, /Bob is typing/);
    await act(async () =>
      liveSockets.at(-1).receive(`typing:${conversation().id}:true`),
    );
    await act(async () => expireTyping());
    assert.doesNotMatch(
      document.body.textContent,
      /Bob is typing/,
      "lost stop events expire",
    );
    await act(async () => {
      for (let i = 0; i < 2; i++)
        draft.dispatchEvent(
          new dom.window.KeyboardEvent("keydown", {
            key: "Enter",
            bubbles: true,
          }),
        );
    });
    assert.equal(
      sendCount,
      1,
      "duplicate Enter is ignored while send is pending",
    );
    assert.equal(draft.value, "Hello Bob", "failed send preserves draft");
    assert.deepEqual(
      typingRequests.at(-1),
      { active: false },
      "send stops typing even if the send fails",
    );
    assert.match(
      document.querySelector('[role="alert"]').textContent,
      /Unable to reach/,
    );
    await click('[aria-label="Back to conversations"]');
    await act(async () =>
      liveSockets.at(-1).receive(`typing:${conversation().id}:true`),
    );
    await click(".conversation-select");
    assert.match(
      document.body.textContent,
      /Bob is typing/,
      "typing received before opening the chat is retained",
    );
    await act(async () => expireTyping());
    assert.doesNotMatch(document.body.textContent, /Bob is typing/);
    assert.equal(document.getElementById("chat-draft").value, "Hello Bob");
    await click('[aria-label="Retry send"]');
    assert.equal(
      sentIds[0],
      sentIds[1],
      "retry reuses the original identifier even after switching away",
    );
    assert.equal(messages.length, 1);
    assert.equal(document.querySelectorAll(".chat-message").length, 1);
    assert.equal(document.getElementById("chat-draft").value, "");
    assert.equal(
      document.activeElement,
      document.getElementById("chat-draft"),
      "after sending, the cursor is back in the text box",
    );
    assert.ok(document.querySelector(".chat-message time"));
    const receiptLabel = () =>
      document
        .querySelector(".chat-message.sent .receipt")
        .getAttribute("aria-label");
    assert.equal(receiptLabel(), "Sent");
    for (const [delivered, read, label] of [
      [1, 0, "Delivered"],
      [1, 1, "Seen"],
    ]) {
      receipts = {
        targetDeliveredSequence: delivered,
        targetReadSequence: read,
      };
      await act(async () => {
        liveSockets.at(-1).receive("refresh");
        await new Promise((resolve) => setTimeout(resolve, 0));
      });
      assert.equal(receiptLabel(), label);
    }
    await input(document.getElementById("chat-draft"), "unsent draft");
    await click('[aria-label="Chat settings"]');
    await click('a[href="/profile"]');
    await click('a[href="/chat"]');
    assert.equal(document.getElementById("chat-draft").value, "unsent draft");
    failHistory = true;
    await tick(3000);
    assert.match(document.body.textContent, /Retrying automatically/);
    failHistory = false;
    // Messages arriving while the window is behind another app are not "seen".
    focused = false;
    for (let i = 2; i <= 65; i++)
      messages.push({
        id: `message-${i}`,
        sequence: i,
        clientId: `client-${i}`,
        text: `Incoming ${i}`,
        senderId: "bob",
        senderName: "Bob",
        sentAt: "2026-09-11T11:00:00Z",
      });
    await act(async () => {
      liveSockets.at(-1).receive("refresh");
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    assert.equal(
      document.querySelectorAll(".chat-message").length,
      65,
      "a WebSocket hint catches up across pages without a polling tick",
    );
    assert.equal(
      document.querySelectorAll(".chat-message.received").length,
      64,
    );
    assert.ok(
      !readRequests.some((r) => r.sequence === 65),
      "an unfocused window does not mark new messages read",
    );
    focused = true;
    await act(async () => {
      window.dispatchEvent(new dom.window.Event("focus"));
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    assert.deepEqual(
      readRequests.at(-1),
      { sequence: 65 },
      "the open conversation is marked read up to its newest message",
    );
    assert.equal(
      document.querySelector(".conversation-header small").textContent,
      "In a meeting · Back at 3",
      "the other person's saved status and note are shown",
    );
    assert.equal(document.getElementById("my-status").value, "busy");
    await act(async () => {
      const select = document.getElementById("my-status");
      Object.getOwnPropertyDescriptor(
        dom.window.HTMLSelectElement.prototype,
        "value",
      ).set.call(select, "meeting");
      select.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
    });
    assert.deepEqual(presenceRequests.at(-1), {
      status: "meeting",
      note: "Saved note",
    });
    await click('.conversation-header [aria-label="Actions for Bob"]');
    await click(
      [...document.querySelectorAll(".action-popover button")].find(
        (b) => b.textContent === "Pin Bob",
      ),
    );
    assert.deepEqual(preferenceRequests.at(-1), { pinned: true });
    await renderUser("alice", "reload");
    assert.ok(
      document.querySelector('[aria-label="PINNED"] .conversation-row'),
      "a pin is saved to the account and survives a reload",
    );
    assert.equal(
      document
        .querySelector('.conversation-flags [aria-label="Pinned"]')
        .getAttribute("title"),
      "Pinned",
      "pinned rows show an icon whose tooltip explains it",
    );
    assert.equal(
      document.querySelector(".conversation-row small"),
      null,
      "pinned rows are not labelled again under the PINNED heading",
    );
    assert.equal(
      document.querySelector(".conversation-header"),
      null,
      "signing in shows the conversation list without opening a chat",
    );
    assert.match(
      document.querySelector(".conversation-row .preview").textContent,
      /Incoming 65/,
      "the list shows each person with their latest message",
    );
    await click(".conversation-select");
    assert.equal(
      document.querySelectorAll(".chat-message").length,
      50,
      "fresh page loads persisted history",
    );
    assert.doesNotMatch(document.body.textContent, /Load older messages/);
    await act(async () => {
      const pane = document.querySelector(".chat-messages");
      pane.scrollTop = 100;
      pane.dispatchEvent(new dom.window.Event("scroll", { bubbles: true }));
      pane.scrollTop = 0;
      pane.dispatchEvent(new dom.window.Event("scroll", { bubbles: true }));
    });
    assert.equal(document.querySelectorAll(".chat-message").length, 65);
    assert.equal(
      document
        .querySelectorAll(".chat-message")[0]
        .textContent.includes("Hello Bob"),
      true,
    );
    await renderUser("alice", "search-jump");
    await click(".conversation-select");
    assert.equal(document.querySelectorAll(".chat-message").length, 50);
    await click('[aria-label="Search messages"]');
    await input(document.getElementById("message-search-input"), "Hello Bob");
    await act(async () => new Promise((resolve) => setTimeout(resolve, 280)));
    await click(".message-search-result");
    assert.equal(
      document.querySelectorAll(".chat-message").length,
      65,
      "search loads older history before jumping",
    );
    assert.equal(document.activeElement.dataset.messageId, messages[0].id);
    assert.ok(document.activeElement.classList.contains("search-highlight"));
    await click('[aria-label="Close message search"]');
    const picker = document.getElementById("chat-file");
    const pick = (file) =>
      act(async () => {
        Object.defineProperty(picker, "files", {
          value: [file],
          configurable: true,
        });
        picker.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
      });
    await pick(new File(["MZ"], "setup.exe"));
    assert.match(
      document.querySelector('[role="alert"]').textContent,
      /not allowed/,
    );
    assert.equal(document.querySelector(".pending-attachments"), null);
    await pick(new File(["%PDF-1.7"], "report.pdf"));
    assert.match(
      document.querySelector(".pending-attachments").textContent,
      /report\.pdf/,
    );
    await click('[aria-label="Send message"]');
    assert.equal(uploads.length, 1, "a file can be sent without a caption");
    assert.equal(uploads[0].text, "");
    assert.equal(uploads[0].name, "report.pdf");
    assert.equal(document.querySelector(".pending-attachments"), null);
    const download = document.querySelector(".file-attachment");
    assert.equal(download.getAttribute("href"), "/api/v1/chat/files/file-1");
    assert.equal(download.getAttribute("download"), "report.pdf");
    // Files picked together are sent as one message carrying all of them.
    await act(async () => {
      Object.defineProperty(picker, "files", {
        value: [
          new File(["%PDF-1.7"], "a.pdf"),
          new File(["%PDF-1.7"], "b.pdf"),
        ],
        configurable: true,
      });
      picker.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
    });
    await click('[aria-label="Send message"]');
    assert.equal(uploads.length, 2, "two files are one upload");
    assert.deepEqual(uploads[1].names, ["a.pdf", "b.pdf"]);
    const last = [...document.querySelectorAll(".chat-message")].at(-1);
    assert.equal(last.querySelectorAll(".file-attachment").length, 2);
    const shown = [];
    window.Notification = class {
      static permission = "granted";
      constructor(title, options) {
        shown.push({ title, ...options });
      }
      close() {}
    };
    const incoming = () =>
      messages.push({
        id: `message-${messages.length + 1}`,
        sequence: messages.length + 1,
        clientId: `client-${messages.length + 1}`,
        text: `Ping ${messages.length + 1}`,
        senderId: "bob",
        senderName: "Bob",
        sentAt: "2026-09-11T13:00:00Z",
      });
    await click('[aria-label="Back to conversations"]');
    incoming();
    saved = { ...saved, unread: 2 };
    await tick(5000);
    assert.match(
      document.title,
      /^\(2\)/,
      "the tab title shows the unread count",
    );
    assert.deepEqual(shown, [
      {
        title: "Bob",
        body: `Ping ${messages.length}`,
        tag: `message-${messages.at(-1).id}`,
      },
    ]);
    await click('.conversation-row [aria-label="Actions for Bob"]');
    await click(
      [...document.querySelectorAll(".action-popover button")].find(
        (b) => b.textContent === "Mute Bob",
      ),
    );
    assert.deepEqual(preferenceRequests.at(-1), { muted: true });
    incoming();
    await tick(5000);
    assert.equal(shown.length, 1, "a muted conversation does not notify");
    assert.doesNotMatch(
      document.title,
      /^\(/,
      "muted conversations are left out of the tab title count",
    );
    saved = { ...saved, unread: 0 };
    delete window.Notification;
    await click(".conversation-select");
    window.confirm = () => true;
    const ownLast = () =>
      [...document.querySelectorAll(".chat-message.sent")].at(-1);
    const menu = async (article, label) => {
      await click(article.querySelector('[aria-label="Message actions"]'));
      assert.equal(
        document.querySelector(".message-popover").parentElement,
        document.body,
        "message actions escape the scrolling message list",
      );
      await click(
        [...document.querySelectorAll(".message-popover button")].find(
          (b) =>
            b.textContent === label || b.getAttribute("aria-label") === label,
        ),
      );
    };
    await menu(ownLast(), "React with 👍");
    assert.equal(actionRequests.at(-1).body.emoji, "👍");
    assert.match(
      ownLast().querySelector(".message-reactions").textContent,
      /👍 1/,
    );
    await menu(ownLast(), "Edit");
    assert.match(
      document.querySelector(".composer-context").textContent,
      /Editing message/,
    );
    await input(document.getElementById("chat-draft"), "Caption added");
    await click('[aria-label="Send message"]');
    assert.deepEqual(actionRequests.at(-1).body, { text: "Caption added" });
    assert.match(ownLast().textContent, /Caption added/);
    assert.match(ownLast().querySelector("time").textContent, /Edited/);
    assert.equal(document.querySelector(".composer-context"), null);
    const quotedId = actionRequests.at(-1).messageId;
    await menu(ownLast(), "Reply");
    assert.match(
      document.querySelector(".composer-context").textContent,
      /Replying to Alice/,
    );
    await input(document.getElementById("chat-draft"), "Answer");
    await click('[aria-label="Send message"]');
    assert.equal(
      lastSendBody.replyToId,
      quotedId,
      "a reply quotes its message",
    );
    const remote = messages.find((m) => m.senderId === "bob");
    Object.assign(remote, {
      text: "Edited remotely",
      edited: true,
      revision: ++revision,
    });
    await act(async () => {
      liveSockets.at(-1).receive("refresh");
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    assert.match(
      document.querySelector(".chat-messages").textContent,
      /Edited remotely/,
      "another member's edit reaches messages already on screen",
    );
    const baselineRevision = revision;
    const batchTargets = messages
      .filter((message) => message.senderId === "bob")
      .slice(0, 3);
    for (const [index, offset] of [1, 1001, 1201].entries())
      Object.assign(batchTargets[index], {
        text: `Recovered change ${index + 1}`,
        edited: true,
        revision: baselineRevision + offset,
      });
    revision = baselineRevision + 1201;
    failChangeAfter = baselineRevision + 500;
    await act(async () => {
      liveSockets.at(-1).receive("refresh");
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    assert.match(
      document.querySelector(".chat-messages").textContent,
      /Recovered change 1/,
    );
    assert.doesNotMatch(
      document.querySelector(".chat-messages").textContent,
      /Recovered change 3/,
    );
    await act(async () => {
      liveSockets.at(-1).receive("refresh");
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    assert.match(
      document.querySelector(".chat-messages").textContent,
      /Recovered change 2/,
    );
    assert.match(
      document.querySelector(".chat-messages").textContent,
      /Recovered change 3/,
    );
    assert.equal(
      changeRequests.filter((value) => value === baselineRevision + 500).length,
      2,
      "a failed change page retries from its last applied cursor",
    );
    assert.ok(
      changeRequests.includes(baselineRevision + 1000),
      "empty change windows still continue without new messages",
    );
    await menu(ownLast(), "Delete for everyone");
    assert.deepEqual(actionRequests.at(-1).body, { everyone: true });
    assert.match(ownLast().textContent, /This message was deleted/);
    await click(".new-group-button");
    await input(document.getElementById("group-name"), "Launch team");
    await input(document.getElementById("group-people"), "bob");
    await act(async () => new Promise((resolve) => setTimeout(resolve, 240)));
    await click('[aria-label="Add Bob"]');
    await click(".bulk-add-open");
    await input(document.getElementById("group-bulk"), "@bob, ghost");
    await click(
      [...document.querySelectorAll(".bulk-add button")].find((b) =>
        b.textContent.startsWith("Add 2"),
      ),
    );
    assert.match(
      document.querySelector(".bulk-add-result").textContent,
      /Added 0 people\. Not found or already added: bob, ghost/,
      "pasted names are looked up together and the misses are listed",
    );
    await click(
      [...document.querySelectorAll(".group-panel button")].find(
        (b) => b.textContent === "Create group",
      ),
    );
    assert.deepEqual(
      groupRequests.find((r) => r.path === "group"),
      {
        path: "group",
        body: { name: "Launch team", memberIds: ["bob"], adminsOnly: false },
      },
    );
    const groupId = groups[0].id;
    assert.equal(
      document.querySelector(".conversation-header h1").textContent,
      "Launch team",
      "a new group opens straight away",
    );
    assert.match(
      document.querySelector(".conversation-header small").textContent,
      /2 members/,
    );
    await click('[aria-label="Group info"]');
    assert.ok(document.querySelector(".group-info"));
    await click('[aria-label="Close group info"]');
    assert.equal(
      document.querySelector(".group-info"),
      null,
      "the close button hides group info",
    );
    await act(async () => new Promise((resolve) => setTimeout(resolve, 0)));
    await input(document.getElementById("chat-draft"), "Hi @Bo");
    assert.match(
      document.querySelector(".mention-suggestions").textContent,
      /Bob@bob/,
      "suggestions show each member's name with their unique username",
    );
    await act(async () =>
      document.getElementById("chat-draft").dispatchEvent(
        new dom.window.KeyboardEvent("keydown", {
          key: "Enter",
          bubbles: true,
        }),
      ),
    );
    assert.equal(
      document.getElementById("chat-draft").value,
      "Hi @bob ",
      "picking inserts the unique username",
    );
    assert.equal(document.querySelector(".mention-suggestions"), null);
    await input(document.getElementById("chat-draft"), "");
    await click('[aria-label="Group info"]');
    await act(async () => new Promise((resolve) => setTimeout(resolve, 0)));
    assert.match(document.querySelector(".group-members").textContent, /Bob/);
    const groupAlerts = [];
    window.Notification = class {
      static permission = "granted";
      constructor(title, options) {
        groupAlerts.push({ title, ...options });
      }
      close() {}
    };
    await tick(5000);
    const groupArrival = (sequence, text, senderId = "bob") => {
      groups = groups.map((g) =>
        g.id === groupId
          ? {
              ...g,
              muted: true,
              lastMessage: {
                id: `group-alert-${sequence}`,
                sequence,
                text,
                senderId,
                senderName: senderId === "bob" ? "Bob" : "Alice",
                sentAt: "2026-09-12T00:00:00Z",
              },
            }
          : g,
      );
      const row = groups.find((group) => group.id === groupId);
      groupArrivals.push([row.lastMessage, row]);
    };
    groupArrival(101, "Hello everyone");
    await tick(5000);
    assert.equal(
      groupAlerts.length,
      0,
      "ordinary muted group messages stay quiet",
    );
    groupArrival(102, "Hi @alice, please check this");
    groupArrival(103, "A newer ordinary message");
    await tick(5000);
    assert.equal(
      groupAlerts.length,
      1,
      "a mention alerts even in an open muted group",
    );
    assert.equal(
      groupAlerts[0].title,
      "You have been mentioned in Launch team",
    );
    assert.match(groupAlerts[0].body, /Bob: Hi @alice/);
    await tick(5000);
    assert.equal(
      groupAlerts.length,
      1,
      "polling does not repeat a mention notification",
    );
    groupArrival(104, "Hi @alice", "alice");
    await tick(5000);
    assert.equal(groupAlerts.length, 1, "self mentions do not notify");
    delete window.Notification;
    await click('[aria-label="Remove Bob from the group"]');
    assert.ok(
      groupRequests.some((r) => r.path === `${groupId}/members/bob/remove`),
      "an admin can remove a member",
    );
    assert.doesNotMatch(
      document.querySelector(".group-members").textContent,
      /Bob/,
    );
    await click(".group-leave");
    assert.equal(groupRequests.at(-1).path, `${groupId}/leave`);
    assert.equal(document.querySelector(".conversation-header"), null);
    assert.ok(
      ![...document.querySelectorAll(".conversation-row")].some((row) =>
        row.textContent.includes("Launch team"),
      ),
      "a group the user left disappears from the list",
    );
    await click(
      [...document.querySelectorAll(".conversation-select")].find((b) =>
        b.textContent.includes("Bob"),
      ),
    );
    await click('.conversation-header [aria-label="Actions for Bob"]');
    await click(
      [...document.querySelectorAll(".action-popover button")].find(
        (b) => b.textContent === "Shared files",
      ),
    );
    assert.ok(document.querySelector(".shared-files"));
    assert.match(
      document.querySelector(".shared-files").textContent,
      /report.pdf/,
    );
    await click(document.querySelector(".shared-file-context"));
    assert.ok(document.activeElement.classList.contains("chat-message"));
    await click('[aria-label="Close shared files"]');
    const clearChat = async () => {
      await click('.conversation-header [aria-label="Actions for Bob"]');
      await click(
        [...document.querySelectorAll(".action-popover button")].find(
          (b) => b.textContent === "Clear chat",
        ),
      );
    };
    const previousCount = document.querySelectorAll(".chat-message").length;
    window.confirm = () => {
      throw new Error("Clear chat must use the in-app dialog");
    };
    await clearChat();
    const confirmClear = () =>
      click(document.querySelector(".clear-chat-confirm"));
    assert.ok(document.querySelector("dialog[open]"));
    assert.match(
      document.querySelector("dialog").textContent,
      /This cannot be undone/,
    );
    assert.equal(document.activeElement.textContent, "Cancel");
    await click(document.activeElement);
    assert.equal(document.querySelector("dialog"), null);
    assert.equal(
      document.querySelectorAll(".chat-message").length,
      previousCount,
      "cancel preserves messages",
    );
    failClear = true;
    await clearChat();
    await confirmClear();
    assert.equal(
      document.querySelectorAll(".chat-message").length,
      previousCount,
      "failed clear preserves messages",
    );
    failClear = false;
    await clearChat();
    await confirmClear();
    assert.equal(document.querySelectorAll(".chat-message").length, 0);
    assert.equal(
      document.querySelector(".chat-toast").textContent.trim(),
      "✓ Chat is cleared",
    );
    assert.ok(
      ![...document.querySelectorAll(".messaging-notice")].some((notice) =>
        /Chat.*clear/i.test(notice.textContent),
      ),
      "cleared message does not remain inline",
    );
    await act(async () => expireClearToast());
    assert.equal(
      document.querySelector(".chat-toast"),
      null,
      "success popup disappears automatically",
    );
    await act(async () => {
      liveSockets.at(-1).receive("refresh");
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    assert.equal(
      document.querySelectorAll(".chat-message").length,
      0,
      "refresh never restores cleared history",
    );
    incoming();
    await act(async () => {
      liveSockets.at(-1).receive("refresh");
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    assert.equal(
      document.querySelectorAll(".chat-message").length,
      1,
      "new messages arrive after clearing",
    );
    saved = { ...saved, historyFromSequence: messages.at(-1).sequence };
    await act(async () => {
      liveSockets.at(-1).receive("refresh");
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    assert.equal(
      document.querySelectorAll(".chat-message").length,
      0,
      "a clear on another tab removes cached history",
    );
    await input(document.getElementById("chat-draft"), "private draft");
    await renderUser("eve");
    assert.equal(
      liveSockets.at(-2).readyState,
      3,
      "account switch closes old socket",
    );
    assert.equal(document.querySelectorAll(".conversation-select").length, 0);
    assert.equal(document.getElementById("chat-draft"), null);
    assert.doesNotMatch(document.body.textContent, /private draft|Hello Bob/);
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
  }
});

test("visible messages drive receipts and incoming messages preserve the reading position", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  let focused = true;
  document.hasFocus = () => focused;
  const prototype = dom.window.HTMLElement.prototype;
  Object.defineProperty(prototype, "clientHeight", {
    get() {
      return 100;
    },
    configurable: true,
  });
  Object.defineProperty(prototype, "scrollHeight", {
    get() {
      return this.children.length * 20;
    },
    configurable: true,
  });
  const positions = new WeakMap();
  Object.defineProperty(prototype, "scrollTop", {
    get() {
      return positions.get(this) ?? 0;
    },
    set(value) {
      positions.set(
        this,
        Math.max(0, Math.min(value, this.scrollHeight - this.clientHeight)),
      );
    },
    configurable: true,
  });
  prototype.getBoundingClientRect = function () {
    if (this.classList.contains("chat-messages"))
      return { top: 0, bottom: 100, height: 100 };
    const index = [...this.parentElement.children].indexOf(this);
    const top = index * 20 - this.parentElement.scrollTop;
    return { top, bottom: top + 20, height: 20 };
  };
  const { createRoot } = await import("react-dom/client");
  const { default: MessageList } = await server.ssrLoadModule(
    "/src/components/chat/MessageList.tsx",
  );
  const root = createRoot(document.getElementById("root"));
  const visible = [];
  const onVisible = (id, sequence) => visible.push([id, sequence]);
  let olderLoads = 0;
  let jumpTo = null;
  const message = (sequence) => ({
    id: `m-${sequence}`,
    sequence,
    senderId: "bob",
    senderName: "Bob",
    text: `Message ${sequence}`,
    sentAt: "2026-10-31T10:00:00Z",
  });
  let messages = Array.from({ length: 10 }, (_, index) => message(index + 1));
  const render = () =>
    act(async () =>
      root.render(
        createElement(MessageList, {
          conversation: { id: "chat", name: "Bob", type: "DIRECT", messages },
          userId: "alice",
          showTimestamps: true,
          forwardTargets: [],
          handlers: {},
          onVisible,
          hasOlder: true,
          onOlder: () => {
            olderLoads++;
          },
          jumpTo,
        }),
      ),
    );
  const scroll = async (position) =>
    act(async () => {
      const pane = document.querySelector(".chat-messages");
      pane.scrollTop = position;
      pane.dispatchEvent(new dom.window.Event("scroll"));
    });
  try {
    await render();
    const pane = document.querySelector(".chat-messages");
    assert.equal(pane.scrollTop, 100);
    assert.deepEqual(visible.at(-1), ["chat", 10]);
    await scroll(0);
    assert.equal(
      olderLoads,
      1,
      "scrolling upward near the top requests history",
    );
    visible.length = 0;
    messages = [...messages, message(11)];
    await render();
    assert.equal(
      pane.scrollTop,
      0,
      "incoming message preserves history position",
    );
    assert.ok(
      visible.every(([, sequence]) => sequence <= 5),
      "offscreen arrivals are not read",
    );
    assert.match(document.body.textContent, /New messages/);
    messages = [message(-1), message(0), ...messages];
    await render();
    assert.equal(
      pane.scrollTop,
      40,
      "prepending history preserves the visible message",
    );
    focused = false;
    visible.length = 0;
    await scroll(160);
    assert.equal(visible.length, 0, "an unfocused window never reports reads");
    focused = true;
    await act(async () => window.dispatchEvent(new dom.window.Event("focus")));
    assert.deepEqual(visible.at(-1), ["chat", 11]);
    await scroll(0);
    messages = [...messages, message(12)];
    await render();
    await act(async () =>
      [...document.querySelectorAll("button")]
        .find((button) => button.textContent.includes("New messages"))
        .click(),
    );
    assert.equal(pane.scrollTop, 180);
    assert.deepEqual(visible.at(-1), ["chat", 12]);
    assert.doesNotMatch(document.body.textContent, /New messages/);
    messages = [...messages, message(13)];
    await render();
    assert.equal(
      pane.scrollTop,
      200,
      "stays at the bottom while following the conversation",
    );
    jumpTo = { id: "m-3", sequence: 3, request: 1 };
    await render();
    assert.equal(document.activeElement.dataset.messageId, "m-3");
    assert.ok(document.activeElement.classList.contains("search-highlight"));
    assert.equal(
      pane.scrollTop,
      40,
      "search jumps to the matching message in context",
    );
  } finally {
    await act(async () => root.unmount());
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
  }
});

test("group search displays server results that are not loaded in the sidebar", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url) => {
    const route = new URL(url, "http://localhost");
    requests.push(route);
    return new Response(
      JSON.stringify(
        route.pathname.endsWith("/conversations/search")
          ? [{ id: "older-group", name: "Archived team", memberCount: 3 }]
          : [],
      ),
      { status: 200 },
    );
  };
  const { createRoot } = await import("react-dom/client");
  const { default: ConversationSearch } = await server.ssrLoadModule(
    "/src/components/chat/ConversationSearch.tsx",
  );
  const root = createRoot(document.getElementById("root"));
  const selected = [];
  try {
    await act(async () =>
      root.render(
        createElement(ConversationSearch, {
          query: "  team  ",
          onQuery() {},
          onSelect: (...args) => selected.push(args),
        }),
      ),
    );
    await act(async () => new Promise((resolve) => setTimeout(resolve, 250)));
    assert.ok(
      requests.some(
        (route) =>
          route.pathname.endsWith("/conversations/search") &&
          route.searchParams.get("q") === "team",
      ),
    );
    const result = document.querySelector(".search-result");
    assert.match(result.textContent, /Archived team/);
    await act(async () => result.click());
    assert.deepEqual(selected, [["GROUP", "older-group", "Archived team"]]);
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
  }
});

test("each chat has message search with highlighted results, pagination and retry", async () => {
  const { default: ChatHeader } = await server.ssrLoadModule(
    "/src/components/chat/ChatHeader.tsx",
  );
  for (const type of ["DIRECT", "GROUP"]) {
    assert.match(
      renderToStaticMarkup(
        createElement(ChatHeader, {
          conversation: { id: type, name: "Team", type },
          onBack() {},
          onAction() {},
          onSearch() {},
          searching: false,
        }),
      ),
      /aria-label="Search messages"/,
    );
  }
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const originalFetch = globalThis.fetch;
  const calls = [];
  let failMore = true;
  const message = (id, sequence, text) => ({
    id,
    sequence,
    senderName: "Bob",
    text,
    sentAt: "2026-10-31T10:00:00Z",
  });
  globalThis.fetch = async (url, options) => {
    assert.equal(options.credentials, "include");
    const route = new URL(url, "http://localhost");
    calls.push(route);
    if (route.searchParams.has("before") && failMore) {
      failMore = false;
      throw new TypeError("offline");
    }
    return new Response(
      JSON.stringify(
        route.searchParams.get("q") === "missing"
          ? { messages: [], hasMore: false }
          : route.searchParams.has("before")
            ? { messages: [message("old", 1, "Earlier HELLO")], hasMore: false }
            : { messages: [message("new", 99, "Hello team")], hasMore: true },
      ),
      { status: 200 },
    );
  };
  const { createRoot } = await import("react-dom/client");
  const { default: MessageSearch } = await server.ssrLoadModule(
    "/src/components/chat/MessageSearch.tsx",
  );
  const root = createRoot(document.getElementById("root"));
  let closed = false;
  const chosenMessages = [];
  const input = async (value) =>
    act(async () => {
      const field = document.getElementById("message-search-input");
      Object.getOwnPropertyDescriptor(
        dom.window.HTMLInputElement.prototype,
        "value",
      ).set.call(field, value);
      field.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    });
  const wait = () =>
    act(async () => new Promise((resolve) => setTimeout(resolve, 280)));
  const click = (text) =>
    act(async () =>
      [...document.querySelectorAll("button")]
        .find((button) => button.textContent === text)
        .click(),
    );
  try {
    await act(async () =>
      root.render(
        createElement(MessageSearch, {
          onSelect: (message) => chosenMessages.push(message.id),
          conversationId: "direct-chat",
          name: "Bob",
          onClose: () => {
            closed = true;
          },
        }),
      ),
    );
    await input("hello");
    await input("  hello  ");
    await wait();
    assert.equal(
      calls[0].pathname,
      "/api/v1/chat/conversations/direct-chat/messages/search",
    );
    assert.equal(calls[0].searchParams.get("q"), "hello");
    assert.equal(document.querySelector("mark").textContent, "Hello");
    await act(async () =>
      document.querySelector(".message-search-result").click(),
    );
    assert.deepEqual(chosenMessages, ["new"]);
    await click("Load more matches");
    assert.ok(document.querySelector('[role="alert"]'));
    await click("Retry search");
    assert.equal(calls.at(-1).searchParams.get("before"), "99");
    assert.equal(document.querySelectorAll(".message-search-result").length, 2);
    assert.match(document.body.textContent, /2 matches/);
    await input("missing");
    assert.doesNotMatch(document.body.textContent, /Hello team/);
    await wait();
    assert.match(document.body.textContent, /No messages found/);
    await act(async () =>
      root.render(
        createElement(MessageSearch, {
          key: "group",
          onSelect: (message) => chosenMessages.push(message.id),
          conversationId: "group-chat",
          name: "Team",
          onClose: () => {
            closed = true;
          },
        }),
      ),
    );
    assert.equal(document.getElementById("message-search-input").value, "");
    await input("hello");
    await wait();
    assert.equal(
      calls.at(-1).pathname,
      "/api/v1/chat/conversations/group-chat/messages/search",
    );
    await act(async () =>
      document.getElementById("message-search-input").dispatchEvent(
        new dom.window.KeyboardEvent("keydown", {
          key: "Escape",
          bubbles: true,
        }),
      ),
    );
    assert.equal(closed, true);
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
  }
});

test("direct and group menus expose shared files and clear chat", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const { createRoot } = await import("react-dom/client");
  const { default: ConversationActions } = await server.ssrLoadModule(
    "/src/components/chat/ConversationActions.tsx",
  );
  const root = createRoot(document.getElementById("root"));
  const calls = [];
  try {
    for (const type of ["DIRECT", "GROUP"]) {
      await act(async () =>
        root.render(
          createElement(ConversationActions, {
            key: type,
            conversation: { id: type, name: type, type },
            onAction() {},
            onSharedFiles: () => calls.push(`${type}:files`),
            onClearChat: () => calls.push(`${type}:clear`),
          }),
        ),
      );
      await act(async () =>
        document.querySelector(".conversation-actions > button").click(),
      );
      await act(async () =>
        [...document.querySelectorAll(".action-popover button")]
          .find((button) => button.textContent === "Shared files")
          .click(),
      );
      await act(async () =>
        document.querySelector(".conversation-actions > button").click(),
      );
      await act(async () =>
        [...document.querySelectorAll(".action-popover button")]
          .find((button) => button.textContent === "Clear chat")
          .click(),
      );
    }
    assert.deepEqual(calls, [
      "DIRECT:files",
      "DIRECT:clear",
      "GROUP:files",
      "GROUP:clear",
    ]);
  } finally {
    await act(async () => root.unmount());
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
  }
});

test("shared files panel paginates, retries and opens a file in chat", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const originalFetch = globalThis.fetch;
  const calls = [];
  let fail = true;
  const file = (sequence, available) => ({
    id: `message-${sequence}`,
    sequence,
    senderName: "Bob",
    sentAt: "2026-10-31T10:00:00Z",
    attachment: {
      id: `file-${sequence}`,
      name: `report-${sequence}.pdf`,
      contentType: "application/pdf",
      size: 100,
      available,
    },
  });
  globalThis.fetch = async (url) => {
    const route = new URL(url, "http://localhost");
    calls.push(route);
    if (route.searchParams.has("before") && fail) {
      fail = false;
      throw new TypeError("offline");
    }
    return new Response(
      JSON.stringify({
        messages: [
          file(
            route.searchParams.has("before") ? 1 : 99,
            !route.searchParams.has("before"),
          ),
        ],
        hasMore: !route.searchParams.has("before"),
      }),
      { status: 200 },
    );
  };
  const { createRoot } = await import("react-dom/client");
  const { default: SharedFiles } = await server.ssrLoadModule(
    "/src/components/chat/SharedFiles.tsx",
  );
  const root = createRoot(document.getElementById("root"));
  const chosen = [];
  try {
    await act(async () =>
      root.render(
        createElement(SharedFiles, {
          conversationId: "group-files",
          name: "Team",
          onClose() {},
          onSelect: (message) => chosen.push(message.id),
        }),
      ),
    );
    assert.equal(
      calls[0].pathname,
      "/api/v1/chat/conversations/group-files/files",
    );
    assert.equal(
      document.querySelector("a.file-attachment").getAttribute("href"),
      "/api/v1/chat/files/file-99",
    );
    await act(async () => document.querySelector(".history-more").click());
    assert.ok(document.querySelector('[role="alert"]'));
    await act(async () =>
      [...document.querySelectorAll("button")]
        .find((button) => button.textContent === "Retry files")
        .click(),
    );
    assert.equal(calls.at(-1).searchParams.get("before"), "99");
    assert.equal(document.querySelectorAll(".shared-file-row").length, 2);
    assert.match(document.body.textContent, /File expired/);
    await act(async () =>
      document.querySelector(".shared-file-context").click(),
    );
    assert.deepEqual(chosen, ["message-99"]);
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
  }
});
