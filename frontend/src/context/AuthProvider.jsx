import { useEffect, useRef, useState } from "react";
import { AuthContext } from "./useAuth";
import {
  getCurrentUser,
  logout,
  changePassword,
} from "../services/authService";

const sessionChangeKey = "messenger.session-change";

// Notify other tabs that the signed-in session changed.
function notifySessionChange() {
  try {
    // Signal only: never share credentials or user data through browser storage.
    window.localStorage.setItem(sessionChangeKey, crypto.randomUUID());
  } catch {
    /* Focus revalidation still works when storage is unavailable. */
  }
}

// Provide authentication state and session actions to the app.
export default function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [initializing, setInitializing] = useState(true);
  const [signingOut, setSigningOut] = useState(false);
  const [logoutError, setLogoutError] = useState("");
  const [sessionNotice, setSessionNotice] = useState("");
  const sessionVersion = useRef(0);
  const logoutPending = useRef(false);

  useEffect(() => {
    let disposed = false;
    // Recheck the server session and ignore stale responses.
    async function refreshSession() {
      if (logoutPending.current) return;
      const version = ++sessionVersion.current;
      try {
        const current = await getCurrentUser();
        if (!disposed && version === sessionVersion.current) setUser(current);
      } catch {
        /* A temporary network failure does not end a known session. */
      } finally {
        if (!disposed && version === sessionVersion.current)
          setInitializing(false);
      }
    }
    // Refresh the session when this tab becomes visible.
    function onVisible() {
      if (document.visibilityState === "visible") void refreshSession();
    }
    // Clear stale account data and recheck a session changed in another tab.
    function onSessionChange(event) {
      if (event.key !== sessionChangeKey || logoutPending.current) return;
      // Hide the previous account while checking the authoritative server session.
      setUser(null);
      setInitializing(true);
      setLogoutError("");
      void refreshSession();
    }
    // Verify the current session after an API request reports expiration.
    function onExpired() {
      // A delayed 401 may belong to a previous account. Verify the current cookie.
      void refreshSession();
    }
    void refreshSession();
    window.addEventListener("focus", refreshSession);
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("storage", onSessionChange);
    window.addEventListener("messenger:session-expired", onExpired);
    return () => {
      disposed = true;
      window.removeEventListener("focus", refreshSession);
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("storage", onSessionChange);
      window.removeEventListener("messenger:session-expired", onExpired);
    };
  }, []);

  // Store the authenticated user and notify other tabs.
  function signIn(current) {
    setSessionNotice("");
    sessionVersion.current += 1;
    setUser(current);
    setInitializing(false);
    setLogoutError("");
    notifySessionChange();
  }

  // End the server session and update local authentication state.
  async function signOut() {
    if (logoutPending.current) return;
    logoutPending.current = true;
    sessionVersion.current += 1;
    setSigningOut(true);
    setLogoutError("");
    try {
      await logout();
      sessionVersion.current += 1;
      setUser(null);
      setInitializing(false);
      notifySessionChange();
    } catch (error) {
      setLogoutError(error.message);
    } finally {
      logoutPending.current = false;
      setSigningOut(false);
    }
  }

  // 2026-09-23: Suppress background revalidation until the password result is known, then clear every tab.
  async function updatePassword(credentials) {
    if (logoutPending.current)
      throw new Error("An account request is already in progress.");
    logoutPending.current = true;
    sessionVersion.current += 1;
    try {
      await changePassword(credentials);
      setSessionNotice("Password changed. Sign in with your new password.");
      sessionVersion.current += 1;
      setUser(null);
      setInitializing(false);
      setLogoutError("");
      notifySessionChange();
    } catch (error) {
      if (error.status === 401) {
        setUser(null);
        notifySessionChange();
      }
      throw error;
    } finally {
      logoutPending.current = false;
    }
  }

  return (
    <AuthContext.Provider
      value={{
        user,
        signIn,
        signOut,
        updatePassword,
        sessionNotice,
        initializing,
        signingOut,
        logoutError,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}
