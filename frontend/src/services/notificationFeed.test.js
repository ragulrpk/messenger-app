import { after, test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "vite";
import { JSDOM } from "jsdom";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
const server = await createServer({
  server: { middlewareMode: true, hmr: false, ws: false },
  appType: "custom",
});
after(() => server.close());
const { connectNotifications } = await server.ssrLoadModule(
  "/src/services/notificationFeed.ts",
);

test("individual notification pages recover bursts and deduplicate concurrent tabs", async () => {
  const dom = new JSDOM("", { url: "http://localhost" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  const originalFetch = globalThis.fetch;
  const shown = [];
  window.Notification = class {
    static permission = "granted";
    constructor(title, options) {
      shown.push({ title, ...options, instance: this });
    }
    close() {}
  };
  let lock = Promise.resolve();
  Object.defineProperty(window.navigator, "locks", {
    value: {
      request(_key, callback) {
        const task = lock.then(callback);
        lock = task.catch(() => {});
        return task;
      },
    },
  });
  let items = [];
  let fail = false;
  globalThis.fetch = async (url) => {
    const route = new URL(url, "http://localhost");
    assert.ok(route.pathname.endsWith("/notifications"));
    if (fail) throw new TypeError("offline");
    const after = route.searchParams.get("after");
    const found =
      after === null
        ? []
        : items.filter((item) => item.position > Number(after));
    const page = found.slice(0, 50);
    return new Response(
      JSON.stringify({
        notifications: page,
        cursor: found.length > 50 ? page.at(-1).position : items.length,
        hasMore: found.length > 50,
      }),
      { status: 200 },
    );
  };
  const first = connectNotifications("alice", () => ({
    conversationId: null,
    watching: false,
  }));
  const second = connectNotifications("alice", () => ({
    conversationId: null,
    watching: false,
  }));
  try {
    await new Promise((resolve) => setTimeout(resolve, 20));
    items = Array.from({ length: 65 }, (_, index) => ({
      position: index + 1,
      userId: "alice",
      conversationId: "12345678-1234-1234-1234-123456789abc",
      messageId: `message-${index}`,
      mentioned: index === 1,
      title: index === 1 ? "Mention" : "Bob",
      body: `Arrival ${index}`,
    }));
    fail = true;
    await first.refresh();
    assert.equal(shown.length, 0);
    fail = false;
    await Promise.all([first.refresh(), second.refresh()]);
    assert.equal(shown.length, 65);
    assert.equal(new Set(shown.map((item) => item.tag)).size, 65);
    assert.equal(shown[1].title, "Mention");
    await first.refresh();
    assert.equal(shown.length, 65);
    let opened;
    window.addEventListener("messenger:open-conversation", (event) => {
      opened = event.detail;
    });
    window.focus = () => {};
    shown[0].instance.onclick();
    assert.equal(opened, "12345678-1234-1234-1234-123456789abc");
  } finally {
    first.close();
    second.close();
    globalThis.fetch = originalFetch;
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
  }
});

test("the notification worker deduplicates push and live events and rejects the previous account", async () => {
  const state = new Map();
  const displayed = [];
  const listeners = new Map();
  const database = {
    close() {},
    transaction() {
      const transaction = {};
      let pending = 0;
      const request = (read) => {
        pending++;
        const result = {};
        queueMicrotask(() => {
          result.result = read();
          result.onsuccess?.();
          pending--;
          queueMicrotask(() => {
            if (!pending) transaction.oncomplete?.();
          });
        });
        return result;
      };
      transaction.objectStore = () => ({
        get: (key) => request(() => state.get(key)),
        put(value, key) {
          state.set(key, value);
          return request(() => key);
        },
        openCursor: () => request(() => null),
      });
      return transaction;
    },
  };
  const indexedDB = {
    open() {
      const request = {};
      queueMicrotask(() => {
        request.result = database;
        request.onsuccess();
      });
      return request;
    },
  };
  const opened = [];
  const self = {
    addEventListener: (type, callback) => listeners.set(type, callback),
    registration: {
      showNotification: async (title, options) =>
        displayed.push({ title, ...options }),
    },
    clients: {
      matchAll: async () => [],
      openWindow: async (url) => opened.push(url),
    },
    location: { origin: "https://messenger.example" },
  };
  vm.runInNewContext(
    await readFile(
      new URL("../../public/notifications-sw.js", import.meta.url),
      "utf8",
    ),
    { self, indexedDB, setTimeout, clearTimeout, URL, MessageChannel },
  );
  const dispatch = async (type, data) => {
    let pending;
    listeners.get(type)({
      ...data,
      waitUntil(value) {
        pending = value;
      },
    });
    await pending;
  };
  await dispatch("message", {
    data: { type: "notification-owner", userId: "alice" },
  });
  const notification = {
    userId: "alice",
    conversationId: "12345678-1234-1234-1234-123456789abc",
    title: "Bob",
    body: "Hello",
    tag: "message-1",
  };
  await dispatch("push", { data: { json: () => notification } });
  await dispatch("message", {
    data: { type: "show-notification", notification },
  });
  assert.equal(displayed.length, 1);
  await dispatch("notificationclick", {
    notification: { data: displayed[0].data, close() {} },
  });
  assert.equal(
    opened[0],
    "/chat?conversation=12345678-1234-1234-1234-123456789abc",
  );
  await dispatch("message", {
    data: { type: "notification-owner", userId: null },
  });
  await dispatch("push", {
    data: { json: () => ({ ...notification, tag: "message-2" }) },
  });
  assert.equal(
    displayed.length,
    1,
    "logged-out accounts cannot display delayed pushes",
  );
});
