import { after, test } from "node:test";
import assert from "node:assert/strict";
import { act, createElement } from "react";
import { JSDOM } from "jsdom";
import { MemoryRouter } from "react-router-dom";
import { createServer } from "vite";

const server = await createServer({
  server: { middlewareMode: true, hmr: false, ws: false },
  appType: "custom",
});
after(() => server.close());
const { initializeTheme, setTheme } = await server.ssrLoadModule(
  "/src/context/theme.ts",
);
const { default: App } = await server.ssrLoadModule("/src/App.jsx");
const { AuthContext } = await server.ssrLoadModule("/src/context/useAuth.js");

test("theme persists across login/logout and reload, syncs tabs, and tolerates blocked storage", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const { createRoot } = await import("react-dom/client");
  const root = createRoot(document.getElementById("root"));
  let stop = () => {};
  const render = (user) =>
    act(async () =>
      root.render(
        createElement(
          MemoryRouter,
          { initialEntries: ["/login"] },
          createElement(
            AuthContext.Provider,
            {
              value: {
                user,
                initializing: false,
                signOut() {},
                signingOut: false,
                logoutError: "",
              },
            },
            createElement(App),
          ),
        ),
      ),
    );
  try {
    window.localStorage.setItem("messenger.theme", "dark");
    stop = initializeTheme();
    assert.equal(
      document.documentElement.dataset.theme,
      "dark",
      "restored before rendering",
    );
    await render(null);
    assert.ok(document.querySelector(".signin-screen"));
    await render({ id: "taylor", name: "Taylor" });
    assert.ok(document.querySelector(".messenger-chat"));
    assert.equal(document.getElementById("app-theme").value, "dark");
    await render(null);
    assert.ok(document.querySelector(".signin-screen"));
    assert.equal(
      document.documentElement.dataset.theme,
      "dark",
      "logout preserves theme",
    );
    await act(async () => setTheme("light"));
    assert.equal(document.documentElement.dataset.theme, "light");
    assert.equal(window.localStorage.getItem("messenger.theme"), "light");
    stop();
    stop = initializeTheme();
    assert.equal(
      document.documentElement.dataset.theme,
      "light",
      "reload preserves selection",
    );
    window.localStorage.setItem("messenger.theme", "dark");
    await act(async () =>
      window.dispatchEvent(
        new dom.window.StorageEvent("storage", {
          key: "messenger.theme",
          newValue: "dark",
        }),
      ),
    );
    assert.equal(
      document.documentElement.dataset.theme,
      "dark",
      "another tab updates login too",
    );
    window.localStorage.setItem("messenger.theme", "invalid");
    await act(async () =>
      window.dispatchEvent(
        new dom.window.StorageEvent("storage", { key: "messenger.theme" }),
      ),
    );
    assert.equal(document.documentElement.dataset.theme, "light");
    stop();
    Object.defineProperty(window, "localStorage", {
      configurable: true,
      get() {
        throw new Error("blocked");
      },
    });
    stop = initializeTheme();
    await act(async () => setTheme("dark"));
    assert.equal(
      document.documentElement.dataset.theme,
      "dark",
      "theme works without storage",
    );
  } finally {
    stop();
    await act(async () => root.unmount());
    dom.window.close();
    delete globalThis.window;
    delete globalThis.document;
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
  }
});
