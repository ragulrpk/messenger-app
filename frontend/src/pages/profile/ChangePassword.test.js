import { after, test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "vite";
import { JSDOM } from "jsdom";
import { act, createElement } from "react";
import { MemoryRouter } from "react-router-dom";
import { validatePasswordChange } from "./passwordValidation.js";

const server = await createServer({
  server: { middlewareMode: true, hmr: false, ws: false },
  appType: "custom",
});
after(() => server.close());

test("password validation rejects short, reused, mismatched and oversized UTF-8 values", () => {
  const values = {
    currentPassword: "original-password",
    newPassword: "replacement-password",
    confirmPassword: "replacement-password",
  };
  assert.deepEqual(validatePasswordChange(values), {});
  assert.ok(
    validatePasswordChange({ ...values, newPassword: "short" }).newPassword,
  );
  assert.ok(
    validatePasswordChange({ ...values, newPassword: values.currentPassword })
      .newPassword,
  );
  assert.ok(
    validatePasswordChange({ ...values, confirmPassword: "wrong" })
      .confirmPassword,
  );
  assert.ok(
    validatePasswordChange({ ...values, newPassword: "é".repeat(37) })
      .newPassword,
  );
  assert.ok(
    validatePasswordChange({ ...values, currentPassword: " " }).currentPassword,
  );
});

test("settings password form validates, shows backend errors, prevents duplicate submit and signs out on success", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  dom.window.HTMLElement.prototype.scrollIntoView = () => {};
  window.WebSocket = undefined;
  const originalFetch = globalThis.fetch;
  let authenticated = true;
  let changes = 0;
  let finish;
  const response = (value, status = 200) =>
    new Response(JSON.stringify(value), { status });
  globalThis.fetch = async (url, options = {}) => {
    if (url.endsWith("/me"))
      return authenticated
        ? response({ user: { id: "user", username: "alice", name: "Alice" } })
        : response({}, 401);
    if (url.endsWith("/csrf"))
      return response({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    if (url.endsWith("/conversations"))
      return response({ conversations: [], nextCursor: null });
    if (url.endsWith("/password")) {
      changes++;
      assert.equal(options.headers["X-CSRF-TOKEN"], "csrf");
      if (changes === 1)
        return response(
          {
            message: "Check fields",
            fieldErrors: { currentPassword: "Current password is incorrect." },
          },
          400,
        );
      return new Promise((resolve) => {
        finish = () => {
          authenticated = false;
          resolve(new Response(null, { status: 204 }));
        };
      });
    }
    throw new Error(`Unexpected ${url}`);
  };
  const { createRoot } = await import("react-dom/client");
  const { default: App } = await server.ssrLoadModule("/src/App.jsx");
  const { default: Provider } = await server.ssrLoadModule(
    "/src/context/AuthProvider.jsx",
  );
  const root = createRoot(document.getElementById("root"));
  const input = async (field, value) =>
    act(async () => {
      const element = document.getElementById(`change-${field}`);
      Object.getOwnPropertyDescriptor(
        dom.window.HTMLInputElement.prototype,
        "value",
      ).set.call(element, value);
      element.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    });
  const submit = async () =>
    act(async () =>
      document
        .querySelector(".password-form")
        .dispatchEvent(
          new dom.window.Event("submit", { bubbles: true, cancelable: true }),
        ),
    );
  try {
    await act(async () =>
      root.render(
        createElement(
          MemoryRouter,
          { initialEntries: ["/profile#password"] },
          createElement(Provider, null, createElement(App)),
        ),
      ),
    );
    assert.equal(document.activeElement.id, "change-currentPassword");
    await submit();
    assert.equal(changes, 0);
    assert.match(document.body.textContent, /Enter a password/);
    await input("currentPassword", "wrong-password");
    await input("newPassword", "replacement-password");
    await input("confirmPassword", "replacement-password");
    assert.equal(
      document.getElementById("change-newPassword").type,
      "password",
    );
    await act(async () =>
      document.querySelector(".password-visibility input").click(),
    );
    assert.equal(document.getElementById("change-newPassword").type, "text");
    await submit();
    assert.match(document.body.textContent, /Current password is incorrect/);
    await input("currentPassword", "correct-password");
    await submit();
    await submit();
    assert.equal(changes, 2);
    assert.equal(document.getElementById("change-newPassword").disabled, true);
    await act(async () => finish());
    assert.match(
      document.body.textContent,
      /Password changed. Sign in with your new password/,
    );
    assert.equal(document.querySelector(".password-form"), null);
    assert.ok(window.localStorage.getItem("messenger.session-change"));
    assert.doesNotMatch(
      JSON.stringify(window.localStorage),
      /replacement-password|correct-password/,
    );
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
  }
});
