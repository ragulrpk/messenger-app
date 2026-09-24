import { after, test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "vite";
import { JSDOM } from "jsdom";

const server = await createServer({
  server: { middlewareMode: true, hmr: false, ws: false },
  appType: "custom",
});
after(() => server.close());
const { connectChatEvents } = await server.ssrLoadModule(
  "/src/services/chatEvents.ts",
);

// 2026-09-23: Deterministic transport tests do not open real sockets or wait for backoff timers.
test("live events use wss, reconnect, refresh on ready and clean up on account change", () => {
  const dom = new JSDOM("", { url: "https://messenger.example/chat" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  const timers = new Map();
  let sequence = 0;
  window.setTimeout = (callback, delay) => {
    timers.set(++sequence, { callback, delay });
    return sequence;
  };
  window.clearTimeout = (id) => timers.delete(id);
  const instances = [];
  class Socket {
    static CLOSED = 3;
    readyState = 1;
    constructor(url) {
      this.url = String(url);
      instances.push(this);
    }
    close(code = 1000) {
      this.readyState = 3;
      this.onclose?.({ code });
    }
    receive(data) {
      this.onmessage?.({ data });
    }
  }
  window.WebSocket = Socket;
  let refreshes = 0;
  let expired = 0;
  window.addEventListener("messenger:session-expired", () => expired++);
  const stop = connectChatEvents(() => refreshes++);
  try {
    assert.equal(
      instances[0].url,
      "wss://messenger.example/api/v1/chat/events",
    );
    instances[0].receive("ready");
    instances[0].receive("heartbeat");
    instances[0].receive("untrusted-command");
    instances[0].receive("refresh");
    assert.equal(refreshes, 2);
    instances[0].close(1006);
    const retry = [...timers.entries()].find(([, value]) => value.delay < 2000);
    assert.ok(retry);
    timers.delete(retry[0]);
    retry[1].callback();
    assert.equal(instances.length, 2);
    instances[1].receive("ready");
    assert.equal(refreshes, 3, "reconnection reconciles missed changes");
    instances[1].close(1008);
    assert.equal(expired, 1);
    assert.ok([...timers.values()].some((value) => value.delay >= 30000));
    stop();
    instances[1].receive("refresh");
    window.dispatchEvent(new window.Event("online"));
    assert.equal(refreshes, 3);
    assert.equal(timers.size, 0);
  } finally {
    stop();
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
  }
});

test("missing WebSocket support leaves polling usable", () => {
  globalThis.window = { WebSocket: undefined };
  try {
    assert.equal(typeof connectChatEvents(() => assert.fail()), "function");
  } finally {
    delete globalThis.window;
  }
});
