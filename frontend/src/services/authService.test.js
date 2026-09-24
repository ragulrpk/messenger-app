import { test } from "node:test";
import assert from "node:assert/strict";
import {
  login,
  getCurrentUser,
  logout,
  changePassword,
  adminResetPassword,
} from "./authService.js";

const credentials = { username: "taylor", password: " secret " };
const endpoint = "http://localhost:8080/api/v1/users/login";
const user = { id: "user-id", username: "taylor", name: "Taylor" };
const csrf = () =>
  new Response(
    JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "test-csrf" }),
  );

test("administrator reset uses CSRF and cookies and preserves credentials", async () => {
  const values = {
    username: "member",
    currentPassword: " admin password ",
    newPassword: " replacement123 ",
    confirmPassword: " replacement123 ",
  };
  const restore = mockApi(async (url, options) => {
    assert.equal(
      url,
      "http://localhost:8080/api/v1/users/admin-password-reset",
    );
    assert.equal(options.method, "POST");
    assert.equal(options.credentials, "include");
    assert.equal(options.headers["X-CSRF-TOKEN"], "test-csrf");
    assert.deepEqual(JSON.parse(options.body), values);
    return new Response(null, { status: 204 });
  });
  try {
    await adminResetPassword(values, endpoint);
  } finally {
    restore();
  }
});

test("profile preserves only the server boolean administrator flag", async () => {
  for (const value of [true, false, "true"]) {
    const restore = mockApi(
      async () =>
        new Response(
          JSON.stringify({ user: { ...user, administrator: value } }),
        ),
    );
    try {
      assert.equal(
        (await getCurrentUser(endpoint)).administrator,
        typeof value === "boolean" ? value : undefined,
      );
    } finally {
      restore();
    }
  }
});

test("password change sends CSRF and cookies, preserves spaces and accepts 204", async () => {
  const values = {
    currentPassword: " old password ",
    newPassword: " new password123 ",
    confirmPassword: " new password123 ",
  };
  const restore = mockApi(async (url, options) => {
    assert.equal(url, "http://localhost:8080/api/v1/users/password");
    assert.equal(options.credentials, "include");
    assert.equal(options.method, "POST");
    assert.equal(options.headers["X-CSRF-TOKEN"], "test-csrf");
    assert.deepEqual(JSON.parse(options.body), values);
    return new Response(null, { status: 204 });
  });
  try {
    await changePassword(values, endpoint);
  } finally {
    restore();
  }
});

function mockApi(handler) {
  const original = globalThis.fetch;
  globalThis.fetch = async (url, options) =>
    url.endsWith("/csrf") ? csrf() : handler(url, options);
  return () => {
    globalThis.fetch = original;
  };
}

test("login gets CSRF and sends credentials, cookie, and token to Spring Boot", async () => {
  const restore = mockApi(async (url, options) => {
    assert.equal(url, endpoint);
    assert.equal(options.method, "POST");
    assert.equal(options.credentials, "include");
    assert.equal(options.headers["Content-Type"], "application/json");
    assert.equal(options.headers["X-CSRF-TOKEN"], "test-csrf");
    assert.deepEqual(JSON.parse(options.body), credentials);
    return new Response(JSON.stringify({ user }));
  });
  try {
    assert.deepEqual(await login(credentials, endpoint), user);
  } finally {
    restore();
  }
});

test("invalid success payloads never authenticate", async () => {
  for (const body of [
    null,
    {},
    { success: false, user },
    { user: { name: "" } },
    { user: { name: " " } },
  ]) {
    const restore = mockApi(async () => new Response(JSON.stringify(body)));
    try {
      await assert.rejects(login(credentials), /invalid login response/);
    } finally {
      restore();
    }
  }
  const restore = mockApi(async () => new Response("not json"));
  try {
    await assert.rejects(login(credentials), /invalid response/);
  } finally {
    restore();
  }
});

test("HTTP errors, network failures, and timeouts reject authentication", async () => {
  for (const [status, message] of [
    [400, /Check your username/],
    [401, /incorrect/],
    [403, /Request denied/],
    [429, /Too many attempts/],
    [502, /backend is unavailable/],
    [500, /Unable to complete/],
  ]) {
    const restore = mockApi(async () => new Response("error", { status }));
    try {
      await assert.rejects(login(credentials), message);
    } finally {
      restore();
    }
  }
  for (const [error, message] of [
    [new TypeError("network"), /Unable to reach/],
    [new DOMException("aborted", "AbortError"), /timed out/],
  ]) {
    const restore = mockApi(async () => {
      throw error;
    });
    try {
      await assert.rejects(login(credentials), message);
    } finally {
      restore();
    }
  }
});

test("invalid credentials mark both fields without disclosing which value failed", async () => {
  const restore = mockApi(
    async () =>
      new Response(
        JSON.stringify({
          code: "INVALID_CREDENTIALS",
          message: "Username or password is incorrect.",
        }),
        { status: 401, headers: { "Content-Type": "application/json" } },
      ),
  );
  try {
    await assert.rejects(login(credentials, endpoint), (error) => {
      assert.deepEqual(error.fieldErrors, {
        username: "Username or password is incorrect.",
        password: "Username or password is incorrect.",
      });
      return true;
    });
  } finally {
    restore();
  }
});

test("backend validation errors are attached to their login fields", async () => {
  const restore = mockApi(
    async () =>
      new Response(
        JSON.stringify({
          code: "INVALID_REQUEST",
          message: "Provide valid request fields.",
          fieldErrors: { username: "Enter your username." },
        }),
        { status: 400, headers: { "Content-Type": "application/json" } },
      ),
  );
  try {
    await assert.rejects(login(credentials, endpoint), (error) => {
      assert.deepEqual(error.fieldErrors, {
        username: "Enter your username.",
      });
      return true;
    });
  } finally {
    restore();
  }
});

test("session lookup returns user or null when signed out", async () => {
  let restore = mockApi(async (url) => {
    assert.ok(url.endsWith("/me"));
    return new Response(JSON.stringify({ user }));
  });
  try {
    assert.deepEqual(await getCurrentUser(), user);
  } finally {
    restore();
  }
  restore = mockApi(async () => new Response("", { status: 401 }));
  try {
    assert.equal(await getCurrentUser(), null);
  } finally {
    restore();
  }
});

test("login and session restoration preserve the supported profile fields only", async () => {
  const profile = {
    ...user,
    phoneNumber: "+91 90000 00000",
    email: "taylor@example.com",
    dateOfBirth: "1990-05-12",
    designation: "Engineer",
    circle: "Central Circle",
    zone: "North Zone",
    division: "Division A",
    dateOfJoining: "2015-06-01",
  };
  let restore = mockApi(
    async () =>
      new Response(
        JSON.stringify({
          user: { ...profile, passwordHash: "must-not-be-returned" },
        }),
      ),
  );
  try {
    assert.deepEqual(await login(credentials), profile);
    assert.deepEqual(await getCurrentUser(), profile);
  } finally {
    restore();
  }
  restore = mockApi(
    async () =>
      new Response(
        JSON.stringify({
          user: { ...user, circle: null, zone: {}, designation: "   " },
        }),
      ),
  );
  try {
    assert.deepEqual(await getCurrentUser(), user);
  } finally {
    restore();
  }
});

test("logout sends a fresh CSRF token and accepts an empty 204 response", async () => {
  const restore = mockApi(async (url, options) => {
    assert.ok(url.endsWith("/logout"));
    assert.equal(options.method, "POST");
    assert.equal(options.headers["X-CSRF-TOKEN"], "test-csrf");
    assert.equal(options.credentials, "include");
    return new Response(null, { status: 204 });
  });
  try {
    await logout();
  } finally {
    restore();
  }
});

test("invalid CSRF response prevents credential submission", async () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return new Response("{}");
  };
  try {
    await assert.rejects(login(credentials), /initialize a secure session/);
    assert.equal(calls, 1);
  } finally {
    globalThis.fetch = original;
  }
});
