import { useEffect, useRef, useState } from "react";
import { useAuth } from "../context/useAuth";
import { reportActivity } from "../services/authService";

const IDLE_MS = 5 * 60 * 1000;
const WARN_MS = 30 * 1000;
const REPORT_MS = 30 * 1000;
// The last time any tab reported activity; tabs share one inactivity clock.
const KEY = "messenger.last-activity-report";
const ACTIVITY = ["pointerdown", "keydown", "wheel", "touchstart", "scroll"];

function shared() {
  try {
    const value = Number(window.localStorage.getItem(KEY));
    return Number.isFinite(value) ? value : 0;
  } catch {
    return 0;
  }
}

function share(at) {
  try {
    window.localStorage.setItem(KEY, String(at));
  } catch {
    // Without storage each tab keeps its own clock.
  }
}

// Signs the person out after five minutes without real use: typing, clicking,
// scrolling or touching in a visible tab. Use is reported to the server at most
// every 30 seconds, and the countdown runs from the last report, so this page
// and the server agree on when the session ends. A warning appears 30 seconds
// before. Background message checks do not count, here or on the server.
export default function IdleGuard() {
  const { endSession } = useAuth();
  const end = useRef(endSession);
  const report = useRef(() => {});
  const [remaining, setRemaining] = useState(null);

  useEffect(() => {
    end.current = endSession;
  }, [endSession]);

  useEffect(() => {
    let reported = 0;
    report.current = (force = false) => {
      const now = Date.now();
      if (!force && now - Math.max(reported, shared()) < REPORT_MS) return;
      reported = now;
      share(now);
      // A refused report (already signed out) surfaces through other requests.
      reportActivity().catch(() => {});
    };
    function active() {
      if (document.visibilityState === "visible") report.current();
    }
    // Opening or reloading the page is use.
    report.current(true);
    for (const type of ACTIVITY)
      window.addEventListener(type, active, { passive: true, capture: true });
    const timer = window.setInterval(() => {
      const idle = Date.now() - Math.max(reported, shared());
      if (idle >= IDLE_MS) {
        window.clearInterval(timer);
        void end.current(
          "You were signed out after 5 minutes without activity.",
        );
        return;
      }
      setRemaining(
        idle >= IDLE_MS - WARN_MS ? Math.ceil((IDLE_MS - idle) / 1000) : null,
      );
    }, 1000);
    return () => {
      for (const type of ACTIVITY)
        window.removeEventListener(type, active, { capture: true });
      window.clearInterval(timer);
    };
  }, []);

  if (remaining === null) return null;
  return (
    <div className="idle-warning" role="alertdialog" aria-live="assertive">
      <p>
        You will be signed out in {remaining} seconds because you have not used
        Let's Talk for a while.
      </p>
      <button type="button" onClick={() => report.current(true)}>
        Stay signed in
      </button>
    </div>
  );
}
