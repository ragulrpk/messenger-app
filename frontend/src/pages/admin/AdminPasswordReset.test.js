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

test("administrator form verifies input, displays server errors, prevents duplicates and clears credentials", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const originalFetch = globalThis.fetch;
  let requests = 0;
  let finish;
  globalThis.fetch = async (url, options) => {
    if (url.endsWith("/csrf"))
      return new Response(
        JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "csrf" }),
      );
    assert.ok(url.endsWith("/admin-password-reset"));
    assert.equal(options.headers["X-CSRF-TOKEN"], "csrf");
    assert.equal(JSON.parse(options.body).username, "member");
    requests++;
    if (requests === 1)
      return new Response(
        JSON.stringify({
          fieldErrors: {
            currentPassword: "Your administrator password is incorrect.",
          },
        }),
        { status: 400 },
      );
    return new Promise((resolve) => {
      finish = () => resolve(new Response(null, { status: 204 }));
    });
  };
  const { createRoot } = await import("react-dom/client");
  const { default: Form } = await server.ssrLoadModule(
    "/src/pages/admin/AdminPasswordReset.jsx",
  );
  const { AuthContext } = await server.ssrLoadModule("/src/context/useAuth.js");
  const root = createRoot(document.getElementById("root"));
  const input = async (field, value) =>
    act(async () => {
      const element = document.getElementById(`admin-${field}`);
      Object.getOwnPropertyDescriptor(
        dom.window.HTMLInputElement.prototype,
        "value",
      ).set.call(element, value);
      element.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    });
  const submit = async () =>
    act(async () =>
      document
        .querySelector("form")
        .dispatchEvent(
          new dom.window.Event("submit", { bubbles: true, cancelable: true }),
        ),
    );
  try {
    await act(async () =>
      root.render(
        createElement(
          MemoryRouter,
          null,
          createElement(
            AuthContext.Provider,
            { value: { user: { username: "ragul", administrator: true } } },
            createElement(Form),
          ),
        ),
      ),
    );
    await submit();
    assert.equal(requests, 0);
    assert.match(document.body.textContent, /Verify the user’s identity/);
    await input("username", "member");
    await input("currentPassword", "wrong-password");
    await input("newPassword", "replacement-password");
    await input("confirmPassword", "replacement-password");
    await act(async () => document.getElementById("admin-verified").click());
    await submit();
    assert.match(
      document.body.textContent,
      /administrator password is incorrect/,
    );
    await input("currentPassword", "correct-admin-password");
    await submit();
    await submit();
    assert.equal(requests, 2);
    assert.equal(document.getElementById("admin-newPassword").disabled, true);
    await act(async () => finish());
    assert.match(
      document.querySelector('[role="status"]').textContent,
      /Password reset for member/,
    );
    for (const field of [
      "username",
      "currentPassword",
      "newPassword",
      "confirmPassword",
    ])
      assert.equal(document.getElementById(`admin-${field}`).value, "");
    assert.equal(document.getElementById("admin-verified").checked, false);
    assert.doesNotMatch(JSON.stringify(window.localStorage), /password/);
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
  }
});
