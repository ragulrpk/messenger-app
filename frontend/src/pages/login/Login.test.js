import { after, test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "vite";
import { JSDOM } from "jsdom";
import { act, createElement } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

const server = await createServer({
  server: { middlewareMode: true, hmr: false, ws: false },
  appType: "custom",
});
after(() => server.close());

function browser() {
  // A visible tab: the inactivity guard only counts use while the page is visible.
  const dom = new JSDOM('<div id="root"></div>', {
    url: "http://localhost/",
    pretendToBeVisual: true,
  });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const input = async (id, value) =>
    act(async () => {
      const element = document.getElementById(id);
      Object.getOwnPropertyDescriptor(
        dom.window.HTMLInputElement.prototype,
        "value",
      ).set.call(element, value);
      element.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    });
  const submit = async (id) =>
    act(async () => {
      document
        .getElementById(id)
        .closest("form")
        .dispatchEvent(
          new dom.window.Event("submit", { bubbles: true, cancelable: true }),
        );
      for (let i = 0; i < 6; i++) await new Promise((r) => setTimeout(r, 0));
    });
  return { dom, input, submit };
}

test("a temporary password continues with a new password", async () => {
  const { input, submit } = browser();
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    if (url.endsWith("/csrf"))
      return new Response(
        JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf" }),
      );
    requests.push([url, JSON.parse(options.body)]);
    if (url.endsWith("/users/login"))
      return new Response(
        JSON.stringify({
          status: "PASSWORD_CHANGE_REQUIRED",
          passwordChangeRequired: true,
        }),
      );
    return new Response(
      JSON.stringify({ user: { id: "1", username: "tn100", name: "Asha" } }),
    );
  };
  const signedIn = [];
  const { createRoot } = await import("react-dom/client");
  const { default: Login } = await server.ssrLoadModule(
    "/src/pages/login/Login.jsx",
  );
  const { AuthContext } = await server.ssrLoadModule("/src/context/useAuth.js");
  const root = createRoot(document.getElementById("root"));
  try {
    await act(async () =>
      root.render(
        createElement(
          AuthContext.Provider,
          {
            value: { signIn: (user) => signedIn.push(user), sessionNotice: "" },
          },
          createElement(
            MemoryRouter,
            { initialEntries: ["/login"] },
            createElement(
              Routes,
              null,
              createElement(Route, {
                path: "/login",
                element: createElement(Login),
              }),
              createElement(Route, { path: "/chat", element: "CHAT" }),
            ),
          ),
        ),
      ),
    );
    assert.match(document.body.textContent, /GPF number or username/);
    await input("username", " TN100 ");
    await input("password", "Start@Pass1");
    await submit("username");
    assert.deepEqual(requests[0][1], {
      username: "TN100",
      password: "Start@Pass1",
    });
    assert.match(
      document.body.textContent,
      /Your password is temporary\. Choose your own password/,
    );
    assert.equal(document.getElementById("code"), null);
    // A weak password is refused here without a request.
    await input("newPassword", "short");
    await input("confirmPassword", "short");
    await submit("newPassword");
    assert.equal(requests.length, 1);
    assert.match(document.body.textContent, /at least 12 characters/i);
    await input("newPassword", "ChosenPassword!1234");
    await input("confirmPassword", "ChosenPassword!1234");
    await submit("newPassword");
    assert.match(requests[1][0], /\/users\/login\/verify$/);
    assert.deepEqual(requests[1][1], {
      newPassword: "ChosenPassword!1234",
      confirmPassword: "ChosenPassword!1234",
    });
    assert.equal(signedIn[0].username, "tn100");
    assert.equal(document.body.textContent, "CHAT");
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
  }
});

test("five minutes without use signs out, after a warning that can keep the session", async () => {
  const { dom } = browser();
  let now = 1_000_000;
  const realNow = Date.now;
  Date.now = () => now;
  const timers = [];
  dom.window.setInterval = (callback) => timers.push(callback);
  dom.window.clearInterval = () => {};
  const reports = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith("/csrf"))
      return new Response(
        JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf" }),
      );
    reports.push(now);
    return new Response(null, { status: 204 });
  };
  const ended = [];
  const { createRoot } = await import("react-dom/client");
  const { default: IdleGuard } = await server.ssrLoadModule(
    "/src/components/IdleGuard.jsx",
  );
  const { AuthContext } = await server.ssrLoadModule("/src/context/useAuth.js");
  const root = createRoot(document.getElementById("root"));
  const tick = (ms) =>
    act(async () => {
      now += ms;
      timers.forEach((callback) => callback());
      for (let i = 0; i < 4; i++) await new Promise((r) => setTimeout(r, 0));
    });
  try {
    await act(async () =>
      root.render(
        createElement(
          AuthContext.Provider,
          { value: { endSession: (notice) => ended.push(notice) } },
          createElement(IdleGuard),
        ),
      ),
    );
    await tick(0);
    assert.equal(reports.length, 1, "opening the app counts as use");
    await tick(4 * 60 * 1000);
    assert.equal(document.body.textContent, "");
    await tick(31 * 1000);
    assert.match(document.body.textContent, /signed out in 29 seconds/);
    // Staying reports use at once and clears the warning.
    await act(async () => {
      document.querySelector(".idle-warning button").click();
      for (let i = 0; i < 4; i++) await new Promise((r) => setTimeout(r, 0));
    });
    assert.equal(reports.length, 2);
    await tick(1000);
    assert.equal(document.body.textContent, "");
    // Use more than 30 seconds after the last report is reported again.
    await tick(40 * 1000);
    await act(async () => {
      dom.window.dispatchEvent(new dom.window.Event("keydown"));
      for (let i = 0; i < 4; i++) await new Promise((r) => setTimeout(r, 0));
    });
    assert.equal(reports.length, 3);
    await tick(5 * 60 * 1000);
    assert.deepEqual(ended, [
      "You were signed out after 5 minutes without activity.",
    ]);
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
    Date.now = realNow;
  }
});
