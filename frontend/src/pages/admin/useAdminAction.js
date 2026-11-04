import { useCallback, useRef, useState } from "react";

// Runs one administrative request at a time and keeps its error for display.
// An ended session (401) returns the app to sign-in with the server's reason.
export default function useAdminAction() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const busy = useRef(false);
  const run = useCallback(async (work) => {
    if (busy.current) return undefined;
    busy.current = true;
    setPending(true);
    setError("");
    try {
      return await work();
    } catch (failure) {
      setError(failure.message || "That did not work. Try again.");
      if (failure.status === 401)
        window.dispatchEvent(
          new window.CustomEvent("messenger:session-expired", {
            detail: { code: failure.code, message: failure.message },
          }),
        );
      return undefined;
    } finally {
      busy.current = false;
      setPending(false);
    }
  }, []);
  return { run, pending, error, setError };
}
