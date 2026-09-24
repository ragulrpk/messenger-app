const loginUrl = import.meta.env?.VITE_AUTH_LOGIN_URL || "/api/v1/users/login";

// Build a related authentication endpoint from the login URL.
function relatedEndpoint(endpoint, action) {
  if (!/\/login\/?$/.test(endpoint))
    throw new Error("The login URL must end with /login.");
  return endpoint.replace(/\/login\/?$/, `/${action}`);
}

// Make a cookie-authenticated request with timeout and user-facing errors.
async function request(url, options = {}, allowAnonymous = false) {
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
        if (payload?.fieldErrors && typeof payload.fieldErrors === "object")
          error.fieldErrors = payload.fieldErrors;
        throw error;
      }
      if (response.status === 401) {
        const error = new Error(
          payload?.message || "Username or password is incorrect.",
        );
        error.status = 401;
        if (payload?.fieldErrors && typeof payload.fieldErrors === "object")
          error.fieldErrors = payload.fieldErrors;
        else if (options.method === "POST" && /\/login\/?$/.test(url))
          error.fieldErrors = {
            username: error.message,
            password: error.message,
          };
        throw error;
      }
      if (response.status === 403)
        throw new Error("Request denied. Refresh the page and try again.");
      if (response.status === 429)
        throw new Error("Too many attempts. Please try again later.");
      if ([502, 503, 504].includes(response.status))
        throw new Error(
          "The backend is unavailable. Check that it has started, then try again.",
        );
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
async function csrfHeaders(endpoint) {
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
  for (const field of [
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

// Submit credentials and return the authenticated user.
export async function login({ username, password }, endpoint = loginUrl) {
  const csrf = await csrfHeaders(endpoint);
  const data = await request(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...csrf },
    body: JSON.stringify({ username, password }),
  });
  return readUser(data);
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

// 2026-09-23: Reauthenticate the administrator and reset a different account using a fresh CSRF token.
export async function adminResetPassword(credentials, endpoint = loginUrl) {
  const csrf = await csrfHeaders(endpoint);
  await request(relatedEndpoint(endpoint, "admin-password-reset"), {
    method: "POST",
    headers: { "Content-Type": "application/json", ...csrf },
    body: JSON.stringify(credentials),
  });
}
