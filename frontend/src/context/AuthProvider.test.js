import { after, test } from "node:test";
import assert from "node:assert/strict";
import { act, createElement } from "react";
import { JSDOM } from "jsdom";
import { createServer } from "vite";

const server = await createServer({
  server: { middlewareMode: true, hmr: false, ws: false },
  appType: "custom",
});
after(() => server.close());
const { default: AuthProvider } = await server.ssrLoadModule(
  "/src/context/AuthProvider.jsx",
);
const { useAuth } = await server.ssrLoadModule("/src/context/useAuth.js");
const alice = { id: "alice", name: "Alice", username: "alice" };
const bob = { id: "bob", name: "Bob", username: "bob" };
const response = (user) =>
  user
    ? new Response(JSON.stringify({ user }))
    : new Response(null, { status: 401 });

test("session revalidation, cross-tab changes, stale responses, and failed logout", async () => {
  const dom = new JSDOM('<div id="root"></div>', {
    url: "http://localhost/",
    pretendToBeVisual: true,
  });
  const originalFetch = globalThis.fetch;
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const { createRoot } = await import("react-dom/client");
  const root = createRoot(document.getElementById("root"));
  let auth;
  let calls = 0;
  let current = alice;
  let failLookup = false;
  let failLogout = false;
  let pending;
  globalThis.fetch = async (url) => {
    if (url.endsWith("/csrf"))
      return new Response(
        JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "test" }),
      );
    if (url.endsWith("/logout"))
      return new Response(null, { status: failLogout ? 500 : 204 });
    calls++;
    if (pending) return pending;
    if (failLookup) throw new TypeError("offline");
    return response(current);
  };
  function Probe() {
    auth = useAuth();
    return createElement("span", null, auth.user?.name ?? "Anonymous");
  }
  const focus = () =>
    act(async () => window.dispatchEvent(new dom.window.Event("focus")));
  const changed = () =>
    act(async () =>
      window.dispatchEvent(
        new dom.window.StorageEvent("storage", {
          key: "messenger.session-change",
          newValue: "changed",
        }),
      ),
    );
  try {
    await act(async () =>
      root.render(createElement(AuthProvider, null, createElement(Probe))),
    );
    assert.equal(auth.user.id, "alice");
    current = bob;
    await focus();
    assert.equal(auth.user.id, "bob");
    current = alice;
    await act(async () =>
      document.dispatchEvent(new dom.window.Event("visibilitychange")),
    );
    assert.equal(auth.user.id, "alice");
    failLookup = true;
    await focus();
    assert.equal(
      auth.user.id,
      "alice",
      "temporary lookup errors preserve a known session",
    );
    await changed();
    assert.equal(
      auth.user,
      null,
      "an external account change hides the old account even if lookup fails",
    );
    assert.equal(auth.initializing, false);
    failLookup = false;
    current = bob;
    await changed();
    assert.equal(auth.user.id, "bob");
    current = null;
    await changed();
    assert.equal(auth.user, null);

    let resolveStale;
    pending = new Promise((resolve) => {
      resolveStale = resolve;
    });
    await focus();
    pending = null;
    current = bob;
    await focus();
    await act(async () => resolveStale(response(alice)));
    assert.equal(
      auth.user.id,
      "bob",
      "older lookups cannot replace a newer session",
    );

    pending = new Promise((resolve) => {
      resolveStale = resolve;
    });
    await focus();
    await act(async () => auth.signIn(alice));
    const signInSignal = window.localStorage.getItem(
      "messenger.session-change",
    );
    assert.ok(signInSignal);
    assert.ok(!signInSignal.includes("alice"));
    pending = null;
    await act(async () => resolveStale(response(bob)));
    assert.equal(
      auth.user.id,
      "alice",
      "sign-in invalidates in-flight lookups",
    );

    current = alice;
    await act(async () =>
      window.dispatchEvent(new window.Event("messenger:session-expired")),
    );
    assert.equal(
      auth.user.id,
      "alice",
      "a stale chat 401 cannot log out a newer valid session",
    );
    current = null;
    await act(async () =>
      window.dispatchEvent(new window.Event("messenger:session-expired")),
    );
    assert.equal(auth.user, null, "chat 401 rechecks the server session");
    await act(async () => auth.signIn(alice));
    const refreshedSignal = window.localStorage.getItem(
      "messenger.session-change",
    );
    failLogout = true;
    await act(async () => auth.signOut());
    assert.equal(auth.user.id, "alice");
    assert.ok(auth.logoutError);
    assert.equal(
      window.localStorage.getItem("messenger.session-change"),
      refreshedSignal,
    );
    failLogout = false;
    await act(async () => auth.signOut());
    assert.equal(auth.user, null);
    assert.notEqual(
      window.localStorage.getItem("messenger.session-change"),
      refreshedSignal,
    );
    await act(async () => root.unmount());
    const before = calls;
    await focus();
    await changed();
    assert.equal(calls, before, "unmount removes event listeners");
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
  }
});
