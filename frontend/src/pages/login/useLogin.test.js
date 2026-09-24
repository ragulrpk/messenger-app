import { test } from "node:test";
import assert from "node:assert/strict";
import { validateLogin } from "./useLogin.js";

test("validation rejects empty or whitespace usernames and empty passwords", () => {
  assert.deepEqual(Object.keys(validateLogin("", "")), [
    "username",
    "password",
  ]);
  assert.ok(validateLogin("   ", "secret").username);
  assert.ok(validateLogin("taylor", "").password);
});

test("validation accepts usernames without imposing email or signup rules", () => {
  assert.deepEqual(validateLogin("taylor", "a"), {});
  assert.deepEqual(validateLogin(" user@example.com ", " secret "), {});
});

test("validation applies backend length limits including multibyte passwords", () => {
  assert.ok(validateLogin("a".repeat(65), "secret").username);
  assert.ok(validateLogin("taylor", "é".repeat(37)).password);
  assert.deepEqual(validateLogin("a".repeat(64), "é".repeat(36)), {});
});
