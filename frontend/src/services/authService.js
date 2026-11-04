const loginUrl = import.meta.env?.VITE_AUTH_LOGIN_URL || "/api/v1/users/login";

// Build a related authentication endpoint from the login URL.
function relatedEndpoint(endpoint, action) {
  if (!/\/login\/?$/.test(endpoint))
    throw new Error("The login URL must end with /login.");
  return endpoint.replace(/\/login\/?$/, `/${action}`);
}

// Make a cookie-authenticated request with timeout and user-facing errors.
// Errors carry the HTTP status and the server's stable code where it sent one.
export async function request(url, options = {}, allowAnonymous = false) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(url, {
      ...options,
      credentials: "include",
      headers: { Accept: "application/json", ...options.headers },
      signal: controller.signal,
    });
    if (response.status === 401 && allowAnonymous) return null;
    if (!response.ok) {
      let payload = null;
      try {
        payload = await response.json();
      } catch {
        // Fall back to status-based messages when the response has no JSON body.
      }
      if (response.status === 400) {
        const error = new Error(
          payload?.message || "Check your username and password and try again.",
        );
        if (typeof payload?.code === "string") error.code = payload.code;
        if (payload?.fieldErrors && typeof payload.fieldErrors === "object")
          error.fieldErrors = payload.fieldErrors;
        throw error;
      }
      if (response.status === 401) {
        const error = new Error(
          payload?.message || "Username or password is incorrect.",
        );
        error.status = 401;
        if (typeof payload?.code === "string") error.code = payload.code;
        if (payload?.fieldErrors && typeof payload.fieldErrors === "object")
          error.fieldErrors = payload.fieldErrors;
        else if (options.method === "POST" && /\/(admin-)?login\/?$/.test(url))
          error.fieldErrors = {
            username: error.message,
            password: error.message,
          };
        throw error;
      }
      if ([403, 404, 409, 429].includes(response.status)) {
        const fallback = {
          403: "Request denied. Refresh the page and try again.",
          404: "That account was not found.",
          409: "That conflicts with the current state. Refresh and try again.",
          429: "Too many attempts. Please try again later.",
        }[response.status];
        const error = new Error(
          typeof payload?.message === "string" ? payload.message : fallback,
        );
        error.status = response.status;
        if (typeof payload?.code === "string") error.code = payload.code;
        throw error;
      }
      if ([502, 503, 504].includes(response.status)) {
        // A specific outage (such as email) explains itself; otherwise the server is down.
        const error = new Error(
          typeof payload?.code === "string" &&
            typeof payload?.message === "string"
            ? payload.message
            : "The backend is unavailable. Check that it has started, then try again.",
        );
        error.status = response.status;
        if (typeof payload?.code === "string") error.code = payload.code;
        throw error;
      }
      throw new Error(
        "Unable to complete the request right now. Please try again.",
      );
    }
    return response.status === 204 ? null : await response.json();
  } catch (error) {
    if (error.name === "AbortError")
      throw new Error("The request timed out. Please try again.", {
        cause: error,
      });
    if (error instanceof TypeError)
      throw new Error(
        "Unable to reach the server. Check your connection and try again.",
        { cause: error },
      );
    if (error instanceof SyntaxError)
      throw new Error("The server returned an invalid response.", {
        cause: error,
      });
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

// Fetch and validate the CSRF token for a state-changing request.
export async function csrfHeaders(endpoint = loginUrl) {
  const csrf = await request(relatedEndpoint(endpoint, "csrf"));
  if (
    csrf?.headerName !== "X-CSRF-TOKEN" ||
    typeof csrf.token !== "string" ||
    !csrf.token
  ) {
    throw new Error(
      "Unable to initialize a secure session. Please refresh and try again.",
    );
  }
  return { [csrf.headerName]: csrf.token };
}

// Validate an authentication response and keep supported profile fields.
function readUser(data) {
  if (
    data?.success === false ||
    typeof data?.user?.name !== "string" ||
    !data.user.name.trim() ||
    typeof data.user.username !== "string" ||
    !data.user.username
  ) {
    throw new Error("The server returned an invalid login response.");
  }
  const profile = {
    id: data.user.id,
    name: data.user.name,
    username: data.user.username,
  };
  // 2026-09-23: This flag controls navigation only; the server independently authorizes every reset.
  if (typeof data.user.administrator === "boolean")
    profile.administrator = data.user.administrator;
  // A temporary password allows nothing but choosing a new one (the server enforces it).
  if (data.user.mustChangePassword === true) profile.mustChangePassword = true;
  // When an administrator last reset the password, so the owner always learns of it.
  if (
    typeof data.user.passwordResetAt === "string" &&
    !Number.isNaN(Date.parse(data.user.passwordResetAt))
  )
    profile.passwordResetAt = data.user.passwordResetAt;
  for (const field of [
    "gpfNumber",
    "cadre",
    "phoneNumber",
    "email",
    "dateOfBirth",
    "designation",
    "circle",
    "zone",
    "division",
    "dateOfJoining",
  ]) {
    if (typeof data.user[field] === "string" && data.user[field].trim())
      profile[field] = data.user[field].trim();
  }
  return profile;
}

// A signed-in user, or the next step: a new password replacing a temporary one.
function readSignIn(data) {
  if (data?.status === "PASSWORD_CHANGE_REQUIRED")
    return { challenge: { passwordChangeRequired: true } };
  return { user: readUser(data) };
}

async function post(url, body, endpoint) {
  const csrf = await csrfHeaders(endpoint);
  return request(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...csrf },
    body: JSON.stringify(body),
  });
}

// Employee sign-in with GPF number or username. Returns { user } or { challenge }.
export async function login({ username, password }, endpoint = loginUrl) {
  return readSignIn(await post(endpoint, { username, password }, endpoint));
}

// Administrator sign-in. Returns { user } or { challenge }.
export async function adminLogin({ username, password }, endpoint = loginUrl) {
  return readSignIn(
    await post(
      relatedEndpoint(endpoint, "admin-login"),
      { username, password },
      endpoint,
    ),
  );
}

// Finish the sign-in in progress with the new password that replaces a
// temporary one; returns the signed-in user.
export async function completeSignIn(values, endpoint = loginUrl) {
  return readUser(
    await post(relatedEndpoint(endpoint, "login/verify"), values, endpoint),
  );
}

// Forgot password: starts a reset for this GPF number or username.
export async function startPasswordReset(username, endpoint = loginUrl) {
  await post(
    relatedEndpoint(endpoint, "password-reset/start"),
    { username },
    endpoint,
  );
}

// Set the new password for the reset in progress; every session of the account ends.
export async function completePasswordReset(values, endpoint = loginUrl) {
  await post(
    relatedEndpoint(endpoint, "password-reset/complete"),
    values,
    endpoint,
  );
}

// Tell the server the person is using the app; only this keeps a session from
// timing out after five idle minutes.
export async function reportActivity(endpoint = loginUrl) {
  await post(relatedEndpoint(endpoint, "activity"), {}, endpoint);
}

// Restore the user from the current server session.
export async function getCurrentUser(endpoint = loginUrl) {
  const data = await request(relatedEndpoint(endpoint, "me"), {}, true);
  return data === null ? null : readUser(data);
}

// End the current session using a fresh CSRF token.
export async function logout(endpoint = loginUrl) {
  // Authentication rotates the CSRF token, so fetch a fresh token before logout.
  const csrf = await csrfHeaders(endpoint);
  await request(relatedEndpoint(endpoint, "logout"), {
    method: "POST",
    headers: csrf,
  });
}

// 2026-09-23: Change only the signed-in account; the server verifies the old password and ends all old sessions.
export async function changePassword(credentials, endpoint = loginUrl) {
  const csrf = await csrfHeaders(endpoint);
  await request(relatedEndpoint(endpoint, "password"), {
    method: "POST",
    headers: { "Content-Type": "application/json", ...csrf },
    body: JSON.stringify(credentials),
  });
}
