import { after, test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "vite";
import { JSDOM } from "jsdom";
import { act, createElement } from "react";
import { MemoryRouter } from "react-router-dom";
const server = await createServer({
  server: { middlewareMode: true, hmr: false, ws: false },
  appType: "custom",
});
after(() => server.close());
const { searchDirectory, sections, applyAction } = await server.ssrLoadModule(
  "/src/components/chat/chatState.ts",
);
const { people, groups, mockConversations } = await server.ssrLoadModule(
  "/src/data/mockChatData.ts",
);
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
test("empty lists produce both empty sections", () => {
  assert.deepEqual(sections([]), { pinned: [], recent: [] });
});

test("persistent chat: directory, failed send, safe retry, catch-up, pagination and account isolation", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  dom.window.HTMLElement.prototype.scrollIntoView = () => {};
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
  window.setInterval = (callback, delay) => {
    intervals.set(delay, callback);
    return delay;
  };
  window.clearInterval = (key) => intervals.delete(key);
  let actor = "alice";
  let opened = false;
  let loseResponse = true;
  let failHistory = false;
  let sendCount = 0;
  const sentIds = [];
  let messages = [];
  const conversation = () => ({
    id: "conversation",
    targetId: "bob",
    name: "Bob",
    updatedAt: messages.at(-1)?.sentAt ?? "2026-09-11T00:00:00Z",
    lastMessage: messages.at(-1) ?? null,
  });
  const response = (value, status = 200) =>
    new Response(JSON.stringify(value), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  globalThis.fetch = async (url, options = {}) => {
    assert.equal(options.credentials, "include");
    const route = new URL(url, "http://localhost");
    if (route.pathname.endsWith("/csrf"))
      return response({ headerName: "X-CSRF-TOKEN", token: "token" });
    if (route.pathname.endsWith("/users")) {
      assert.equal(route.searchParams.get("q").trim().toLowerCase(), "bob");
      return response([{ id: "bob", username: "bob", name: "Bob" }]);
    }
    if (route.pathname.endsWith("/direct")) {
      assert.deepEqual(JSON.parse(options.body), { userId: "bob" });
      assert.equal(options.headers["X-CSRF-TOKEN"], "token");
      opened = true;
      return response(conversation());
    }
    if (route.pathname.endsWith("/conversations"))
      return response({
        conversations: opened && actor === "alice" ? [conversation()] : [],
        nextCursor: null,
      });
    if (options.method === "POST") {
      const body = JSON.parse(options.body);
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
        (!before || m.sequence < Number(before)) &&
        (after === null || m.sequence > Number(after)),
    );
    const page = after === null ? matching.slice(-50) : matching.slice(0, 50);
    return response({ messages: page, hasMore: matching.length > 50 });
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
              user: { id, name: id },
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
      intervals.get(delay)?.();
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
    await input(document.getElementById("people-search"), "  BOB  ");
    assert.match(document.body.textContent, /Searching/);
    await act(async () => new Promise((resolve) => setTimeout(resolve, 240)));
    await click(".search-result");
    assert.equal(document.querySelector("h1").textContent, "Bob");
    assert.match(document.body.textContent, /beginning of your conversation/);
    assert.equal(
      document.querySelector('[aria-label="Attachments coming soon"]').disabled,
      true,
    );
    const draft = document.getElementById("chat-draft");
    await input(draft, "Hello Bob");
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
    assert.match(
      document.querySelector('[role="alert"]').textContent,
      /Unable to reach/,
    );
    await click('[aria-label="Back to conversations"]');
    await click(".conversation-select");
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
    assert.ok(document.querySelector(".chat-message time"));
    await input(document.getElementById("chat-draft"), "unsent draft");
    await click('[aria-label="Chat settings"]');
    await click('a[href="/profile"]');
    await click('a[href="/chat"]');
    assert.equal(document.getElementById("chat-draft").value, "unsent draft");
    failHistory = true;
    await tick(3000);
    assert.match(document.body.textContent, /Retrying automatically/);
    failHistory = false;
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
    await renderUser("alice", "reload");
    assert.equal(
      document.querySelector(".conversation-header h1").textContent,
      "Bob",
      "the newest saved conversation opens automatically",
    );
    assert.equal(
      document.querySelectorAll(".chat-message").length,
      50,
      "fresh page loads persisted history",
    );
    await click(
      [...document.querySelectorAll("button")].find(
        (b) => b.textContent === "Load older messages",
      ),
    );
    assert.equal(document.querySelectorAll(".chat-message").length, 65);
    assert.equal(
      document
        .querySelectorAll(".chat-message")[0]
        .textContent.includes("Hello Bob"),
      true,
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
