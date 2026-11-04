import { getNotifications } from "./chatService";
import {
  bindNotificationAccount,
  configureBackgroundNotifications,
  notify,
  openNotificationConversation,
} from "./notifications";

// One shared cursor per account; Web Locks keep multiple tabs from consuming the same arrivals.
export function connectNotifications(
  userId: string,
  context: () => { conversationId: string | null; watching: boolean },
) {
  const storageKey = `messenger.notification-cursor:${userId}`;
  let cursor: number | undefined;
  let active = true;
  let busy = false;
  let queued = false;
  let live = false;
  let lastRefresh = 0;
  let settle: number | undefined;
  const ready = bindNotificationAccount(userId);
  function savedCursor() {
    try {
      const saved = window.localStorage.getItem(storageKey);
      if (saved !== null && /^\d+$/.test(saved))
        cursor = Math.max(cursor ?? 0, Number(saved));
    } catch {
      /* A per-tab cursor still works when storage is unavailable. */
    }
  }
  async function drain() {
    await ready;
    if (!active) return;
    lastRefresh = Date.now();
    savedCursor();
    for (let batch = 0; active && batch < 20; batch++) {
      const page = await getNotifications(cursor);
      if (!active) return;
      for (const item of page.notifications) {
        if (item.userId !== userId || item.position <= (cursor ?? 0)) continue;
        const current = context();
        if (!(
          current.watching &&
          current.conversationId === item.conversationId &&
          !item.mentioned
        ))
          await notify(
            item.title,
            item.body,
            `message-${item.messageId}`,
            item.conversationId,
            userId,
            item.mentioned,
          );
        cursor = item.position;
        try {
          window.localStorage.setItem(storageKey, String(cursor));
        } catch {
          /* In-memory recovery remains available. */
        }
      }
      cursor = page.cursor;
      try {
        window.localStorage.setItem(storageKey, String(cursor));
      } catch {
        /* Continue in memory. */
      }
      if (!page.hasMore) {
        // Committed arrivals are held back only until an older send commits.
        if (page.pending && settle === undefined)
          settle = window.setTimeout(() => {
            settle = undefined;
            void refresh();
          }, 1000);
        return;
      }
    }
    if (active) queued = true;
  }
  async function refresh() {
    if (!active) return;
    if (busy) {
      queued = true;
      return;
    }
    busy = true;
    try {
      if (window.navigator.locks)
        await window.navigator.locks.request(
          `messenger-notifications:${userId}`,
          drain,
        );
      else await drain();
    } catch {
      /* Keep the cursor: reconnect/polling retries without losing arrivals. */
    } finally {
      busy = false;
      if (active && queued) {
        queued = false;
        window.setTimeout(() => void refresh(), 0);
      }
    }
  }
  const onMessage = (event: MessageEvent) => {
    if (event.data?.type === "notification-context")
      event.ports[0]?.postMessage({ userId, ...context() });
    if (
      event.data?.type === "open-conversation" &&
      event.data.userId === userId
    )
      openNotificationConversation(event.data.conversationId);
  };
  window.navigator.serviceWorker?.addEventListener("message", onMessage);
  void configureBackgroundNotifications(userId);
  void refresh();
  const timer = window.setInterval(() => {
    if (!live || Date.now() - lastRefresh >= 30000) void refresh();
  }, 5000);
  window.addEventListener("online", refresh);
  return {
    refresh,
    setLive(value: boolean) {
      live = value;
    },
    close() {
      active = false;
      window.clearInterval(timer);
      window.clearTimeout(settle);
      window.removeEventListener("online", refresh);
      window.navigator.serviceWorker?.removeEventListener("message", onMessage);
    },
  };
}
