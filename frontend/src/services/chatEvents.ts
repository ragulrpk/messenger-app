/** 2026-09-23: Live invalidation hints; message data still comes from authorized REST requests. */
export function connectChatEvents(onRefresh: () => void) {
  if (typeof window.WebSocket !== "function") return () => {};
  const auth = import.meta.env?.VITE_AUTH_LOGIN_URL || "/api/v1/users/login";
  const base =
    import.meta.env?.VITE_CHAT_API_URL ||
    auth.replace(/\/users\/login\/?$/, "/chat");
  const url = new URL(
    `${base.replace(/\/$/, "")}/events`,
    window.location.href,
  );
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  let socket: WebSocket | null = null;
  let stopped = false;
  let attempt = 0;
  let retry: number | undefined;
  let watchdog: number | undefined;

  // Detect a silently broken connection, including a proxy/network that drops frames.
  function armWatchdog() {
    window.clearTimeout(watchdog);
    watchdog = window.setTimeout(() => socket?.close(), 45000);
  }
  function reconnect() {
    if (stopped || retry !== undefined) return;
    const delay = Math.min(30000, 1000 * 2 ** Math.min(attempt++, 5));
    retry = window.setTimeout(
      () => {
        retry = undefined;
        connect();
      },
      delay + Math.floor(Math.random() * 250),
    );
  }
  function connect() {
    if (stopped) return;
    try {
      const current = new window.WebSocket(url);
      socket = current;
      armWatchdog();
      current.onmessage = (event) => {
        if (stopped || socket !== current) return;
        armWatchdog();
        if (event.data === "ready") {
          attempt = 0;
          onRefresh(); // Recover changes missed before connection or during a disconnect.
        } else if (event.data === "refresh") onRefresh();
      };
      current.onerror = () => current.close();
      current.onclose = (event) => {
        if (stopped || socket !== current) return;
        window.clearTimeout(watchdog);
        socket = null;
        if (event.code === 1008) {
          window.dispatchEvent(new window.Event("messenger:session-expired"));
          attempt = 5;
        }
        reconnect();
      };
    } catch {
      reconnect(); // Unsupported/blocked connections leave HTTP polling available.
    }
  }
  function resume() {
    if (stopped) return;
    onRefresh();
    if (!socket || socket.readyState === window.WebSocket.CLOSED) {
      window.clearTimeout(retry);
      retry = undefined;
      connect();
    }
  }
  function visible() {
    if (document.visibilityState === "visible") resume();
  }
  connect();
  window.addEventListener("online", resume);
  document.addEventListener("visibilitychange", visible);
  return () => {
    stopped = true;
    window.clearTimeout(retry);
    window.clearTimeout(watchdog);
    window.removeEventListener("online", resume);
    document.removeEventListener("visibilitychange", visible);
    socket?.close(); // Account change/logout/unmount cannot retain the previous connection.
  };
}
