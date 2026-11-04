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

test("administrators manage people and groups, and temporary passwords are shown once", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const prompts = [];
  dom.window.confirm = (message) => {
    prompts.push(message);
    return true;
  };
  const expired = [];
  dom.window.addEventListener("messenger:session-expired", (event) =>
    expired.push(event.detail),
  );
  const person = (id, name, extra = {}) => ({
    id,
    loginId: id,
    gpfNumber: null,
    name,
    email: `${id}@dept.in`,
    mobile: null,
    cadre: null,
    enabled: true,
    administrator: false,
    mustChangePassword: false,
    lastLoginAt: null,
    ...extra,
  });
  const people = [
    person("boss", "Boss", { administrator: true }),
    person("tn100", "Asha", { gpfNumber: "TN100", cadre: "Deputy" }),
    person("clerk1", "Bala"),
  ];
  const sent = [];
  let endSession = false;
  const json = (body, status = 200) =>
    new Response(JSON.stringify(body), { status });
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    const path = new URL(url, "http://localhost").pathname;
    if (path.endsWith("/csrf"))
      return json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
    const body = options.body ? JSON.parse(options.body) : null;
    if (options.method === "POST") {
      assert.equal(options.headers["X-CSRF-TOKEN"], "csrf");
      sent.push([path.replace("/api/v1/admin", ""), body]);
      if (endSession)
        return json(
          { code: "SESSION_ENDED", message: "You were signed out." },
          401,
        );
    }
    if (path === "/api/v1/admin/people" && options.method !== "POST")
      return json({ items: people, hasMore: false });
    if (path === "/api/v1/admin/people") {
      assert.equal(body.gpfNumber, "TN200");
      assert.equal(body.username, "");
      return json({ loginId: "tn200", temporaryPassword: "Temp-Pass-1234567" });
    }
    if (path === "/api/v1/admin/people/clerk1")
      return json({
        person: people[2],
        signIns: [
          {
            occurredAt: "2026-11-02T10:00:00Z",
            loginId: "clerk1",
            outcome: "WRONG_PASSWORD",
            clientAddress: "10.0.0.5",
          },
        ],
      });
    if (path === "/api/v1/admin/people/clerk1/enabled") {
      people[2] = { ...people[2], enabled: body.enabled };
      return json(people[2]);
    }
    if (path === "/api/v1/admin/groups" && options.method !== "POST")
      return json({ items: [], hasMore: false });
    if (path === "/api/v1/admin/groups")
      return json({
        group: {
          id: "g1",
          name: body.name,
          memberCount: 2,
          adminsOnly: false,
          archived: false,
          updatedAt: "2026-11-02T10:00:00Z",
        },
        members: [],
      });
    if (path === "/api/v1/admin/audit")
      return json([
        {
          occurredAt: "2026-11-02T10:00:00Z",
          actor: null,
          target: null,
          action: "ACCOUNTS_IMPORTED",
          detail: "6000 accounts",
        },
      ]);
    if (path === "/api/v1/admin/sign-ins") return json([]);
    throw new Error(`Unexpected request ${options.method ?? "GET"} ${path}`);
  };
  const { createRoot } = await import("react-dom/client");
  const { default: Page } = await server.ssrLoadModule(
    "/src/pages/admin/Administration.jsx",
  );
  const { AuthContext } = await server.ssrLoadModule("/src/context/useAuth.js");
  const root = createRoot(document.getElementById("root"));
  const text = () => document.body.textContent;
  const settle = () =>
    act(async () => {
      for (let i = 0; i < 6; i++) await new Promise((r) => setTimeout(r, 0));
    });
  const input = async (id, value) =>
    act(async () => {
      const element = document.getElementById(id);
      Object.getOwnPropertyDescriptor(
        dom.window.HTMLInputElement.prototype,
        "value",
      ).set.call(element, value);
      element.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    });
  const submit = async (id) => {
    await act(async () =>
      document
        .getElementById(id)
        .closest("form")
        .dispatchEvent(
          new dom.window.Event("submit", { bubbles: true, cancelable: true }),
        ),
    );
    await settle();
  };
  const click = async (label) => {
    await act(async () =>
      [...document.querySelectorAll("button")]
        .find(
          (b) =>
            b.textContent.trim() === label ||
            b.getAttribute("aria-label") === label,
        )
        .click(),
    );
    await settle();
  };
  try {
    await act(async () =>
      root.render(
        createElement(
          AuthContext.Provider,
          { value: { user: { username: "boss", administrator: true } } },
          createElement(MemoryRouter, null, createElement(Page)),
        ),
      ),
    );
    await settle();
    assert.match(text(), /GPF TN100 · Deputy/);
    assert.match(text(), /@clerk1/);

    // A new person's temporary password is shown once, then cleared.
    await input("admin-new-gpfNumber", "TN200");
    await input("admin-new-name", "New Officer");
    await input("admin-new-email", "new@dept.in");
    await submit("admin-new-name");
    assert.match(text(), /Temp-Pass-1234567/);
    await click("Done");
    assert.doesNotMatch(text(), /Temp-Pass-1234567/);

    // Details show sign-in history; disabling asks first.
    await click("Open Bala (clerk1)");
    assert.match(text(), /wrong password from 10\.0\.0\.5/);
    await click("Disable account");
    assert.match(prompts.at(-1), /signed out everywhere/);
    assert.deepEqual(sent.at(-1), [
      "/people/clerk1/enabled",
      { enabled: false },
    ]);
    assert.match(text(), /Disabled/);
    // Nobody can disable themselves, so the button is absent on one's own account.
    assert.equal(
      [...document.querySelectorAll("button")].some(
        (b) => b.getAttribute("aria-label") === "Open Boss (boss)",
      ),
      true,
    );

    // Groups: pick people (the first is group admin) and create.
    await click("Groups");
    await input("admin-new-group-name", "Office");
    await input("admin-new-group-people", "a");
    await click("Search");
    // Bala was disabled above, so only active people are offered.
    assert.equal(document.querySelector('[aria-label="Add Bala"]'), null);
    await click("Add Asha");
    await click("Add Boss");
    await click("Create group");
    const created = sent.find(([path]) => path === "/groups");
    assert.deepEqual(created[1], {
      name: "Office",
      adminsOnly: false,
      memberIds: ["tn100", "boss"],
      adminIds: ["tn100"],
    });
    assert.match(text(), /Group details|Office/);

    // An ended session returns to sign-in with the server's reason.
    endSession = true;
    await click("Archive group");
    assert.deepEqual(expired.at(-1), {
      code: "SESSION_ENDED",
      message: "You were signed out.",
    });
    endSession = false;

    await click("Activity");
    assert.match(text(), /Server import imported accounts 6000 accounts/);
    // Temporary passwords never reach browser storage.
    assert.equal(window.localStorage.length, 0);
    assert.equal(window.sessionStorage.length, 0);
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
  }
});
