import { after, test } from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { createServer } from "vite";
import { createServer as createHttpServer } from "node:http";
import viteConfig from "../vite.config.js";

const server = await createServer({
  server: { middlewareMode: true, hmr: false, ws: false },
  appType: "custom",
});
after(() => server.close());
const { default: App } = await server.ssrLoadModule("/src/App.jsx");
const { AuthContext } = await server.ssrLoadModule("/src/context/useAuth.js");

test("administrator navigation is visible only to the server-designated administrator", () => {
  const member = {
    initializing: false,
    user: {
      id: "member",
      username: "member",
      name: "Member",
      administrator: false,
    },
  };
  const admin = {
    initializing: false,
    user: {
      id: "admin",
      username: "ragul",
      name: "Ragul",
      administrator: true,
    },
  };
  assert.equal(
    render("/admin/password-reset", { initializing: false, user: null }),
    "",
  );
  assert.doesNotMatch(
    render("/admin/password-reset", member),
    /id="admin-reset-title"/,
  );
  assert.doesNotMatch(render("/chat", member), /Reset a user/);
  assert.match(render("/chat", admin), /Reset a user/);
  assert.match(
    render("/admin/password-reset", admin),
    /Your administrator password/,
  );
});

test("login provides administrator-assisted forgot-password instructions without a public reset form", () => {
  const html = render("/login", { initializing: false, user: null });
  assert.match(html, /Forgot password\?/);
  assert.match(html, /Contact your administrator/);
  assert.doesNotMatch(html, /id="admin-newPassword"/);
});

function render(path, auth) {
  return renderToString(
    createElement(
      MemoryRouter,
      { initialEntries: [path] },
      createElement(AuthContext.Provider, { value: auth }, createElement(App)),
    ),
  );
}

for (const path of ["/", "/login"]) {
  test(`${path} waits for session restoration before showing login`, () => {
    const html = render(path, { initializing: true, user: null });
    assert.match(html, /Restoring your session/);
    assert.doesNotMatch(html, /<form/);
  });

  test(`${path} shows login only for signed-out users`, () => {
    assert.match(render(path, { initializing: false, user: null }), /<form/);
    // Navigate renders no login form; its navigation runs when mounted in the browser.
    assert.equal(
      render(path, { initializing: false, user: { name: "Taylor" } }),
      "",
    );
  });
}

test("/chat keeps the existing authentication guard", () => {
  assert.match(
    render("/chat", { initializing: true, user: null }),
    /Restoring your session/,
  );
  assert.equal(render("/chat", { initializing: false, user: null }), "");
  const html = render("/chat", {
    initializing: false,
    user: { id: "current", name: "Taylor" },
  });
  assert.match(html, /Search people/);
  assert.match(html, /Search for a person/);
  assert.match(html, /PINNED/);
});

test("/profile protects direct visits and displays all requested profile fields", () => {
  assert.equal(render("/profile", { initializing: false, user: null }), "");
  assert.match(
    render("/profile", { initializing: true, user: null }),
    /Restoring your session/,
  );
  const html = render("/profile", {
    initializing: false,
    user: {
      id: "current",
      name: "Taylor",
      phoneNumber: "+91 90000 00000",
      email: "taylor@example.com",
      dateOfBirth: "1990-05-12",
      designation: "Engineer",
      circle: "Central Circle",
      zone: "North Zone",
      division: "Division A",
      dateOfJoining: "2015-06-01",
    },
  });
  for (const text of [
    "Your profile",
    "+91 90000 00000",
    "taylor@example.com",
    "12 May 1990",
    "Engineer",
    "Central Circle",
    "North Zone",
    "Division A",
    "01 Jun 2015",
    "Back to chat",
  ])
    assert.ok(html.includes(text));
});

test("Vite replaces spoofed forwarding headers with the connected client address", async (t) => {
  const backend = createHttpServer((request, response) => {
    response.setHeader("Content-Type", "application/json");
    response.end(JSON.stringify(request.headers));
  });
  await new Promise((resolve) => backend.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => backend.close(resolve)));
  const proxy = await createServer({
    configFile: false,
    server: {
      host: "127.0.0.1",
      port: 0,
      hmr: false,
      ws: false,
      proxy: {
        "/api": {
          ...viteConfig.server.proxy["/api"],
          target: `http://127.0.0.1:${backend.address().port}`,
        },
      },
    },
  });
  t.after(() => proxy.close());
  await proxy.listen();
  const response = await fetch(
    `http://127.0.0.1:${proxy.httpServer.address().port}/api/check`,
    {
      headers: {
        "X-Forwarded-For": "192.0.2.99",
        "X-Forwarded-Proto": "https",
        Forwarded: "for=192.0.2.99",
        "X-Forwarded-Host": "spoofed.example",
      },
    },
  );
  const headers = await response.json();
  assert.equal(headers["x-forwarded-for"], "127.0.0.1");
  assert.equal(headers["x-forwarded-proto"], "http");
  assert.equal(headers.forwarded, undefined);
  assert.equal(headers["x-forwarded-host"], undefined);
});
