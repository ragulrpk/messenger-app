import { getPushConfig, savePushSubscription } from "./chatService";
export type NotificationState = NotificationPermission | "unsupported";
export type PushState =
  "off" | "enabled" | "unconfigured" | "unsupported" | "error";
let pushState: PushState = "off";
let desiredAccount: string | null = null;
let accountVersion = 0;
export const backgroundNotificationState = () => pushState;
function pushStatus(value: PushState) {
  pushState = value;
  window.dispatchEvent(new window.Event("messenger:push-status"));
}
export function notificationState(): NotificationState {
  return typeof window !== "undefined" &&
    typeof window.Notification === "function"
    ? window.Notification.permission
    : "unsupported";
}
async function worker(create = true) {
  if (!("serviceWorker" in window.navigator)) return undefined;
  const existing = await window.navigator.serviceWorker.getRegistration("/");
  if (!existing && !create) return undefined;
  const registration =
    existing ??
    (await window.navigator.serviceWorker.register("/notifications-sw.js", {
      scope: "/",
      updateViaCache: "none",
    }));
  if (registration.active) return registration;
  return window.navigator.serviceWorker.ready;
}
function workerMessage(target: ServiceWorker, message: unknown): Promise<void> {
  return new Promise((resolve, reject) => {
    const channel = new MessageChannel();
    const timer = window.setTimeout(() => {
      channel.port1.close();
      reject(new Error("Notification worker timed out"));
    }, 3000);
    channel.port1.onmessage = (event) => {
      window.clearTimeout(timer);
      channel.port1.close();
      if (event.data?.ok) resolve();
      else reject(new Error("Notification could not be displayed"));
    };
    target.postMessage(message, [channel.port2]);
  });
}
export async function bindNotificationAccount(userId: string | null) {
  desiredAccount = userId;
  const request = ++accountVersion;
  try {
    const target = (await worker(userId !== null))?.active;
    if (target && request === accountVersion)
      await workerMessage(target, { type: "notification-owner", userId });
  } catch {
    /* Desktop notifications still work without a service worker. */
  }
}
export async function clearNotificationDevice() {
  await bindNotificationAccount(null);
  pushStatus("off");
}
export async function configureBackgroundNotifications(
  userId: string,
  create = false,
) {
  if (!("serviceWorker" in window.navigator) || !("PushManager" in window)) {
    pushStatus("unsupported");
    return;
  }
  if (notificationState() !== "granted") {
    pushStatus("off");
    return;
  }
  try {
    const config = await getPushConfig();
    if (desiredAccount !== userId) return;
    if (!config.enabled) {
      pushStatus("unconfigured");
      return;
    }
    const registration = await worker();
    if (!registration) {
      pushStatus("unsupported");
      return;
    }
    if (desiredAccount !== userId) return;
    await bindNotificationAccount(userId);
    let subscription = await registration.pushManager.getSubscription();
    if (desiredAccount !== userId) return;
    if (!subscription && !create) {
      pushStatus("off");
      return;
    }
    if (!subscription) {
      const padded = config.publicKey.replace(/-/g, "+").replace(/_/g, "/");
      const key = Uint8Array.from(window.atob(padded), (value) =>
        value.charCodeAt(0),
      );
      subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: key,
      });
    }
    if (desiredAccount !== userId) return;
    await savePushSubscription(subscription.toJSON());
    if (desiredAccount === userId) pushStatus("enabled");
  } catch {
    pushStatus("error");
  }
}
export async function requestNotifications(
  userId?: string,
): Promise<NotificationState> {
  if (notificationState() === "unsupported") return "unsupported";
  try {
    const permission = await window.Notification.requestPermission();
    if (permission === "granted" && userId)
      await configureBackgroundNotifications(userId, true);
    return permission;
  } catch {
    return notificationState();
  }
}
export function openNotificationConversation(conversationId: string) {
  window.dispatchEvent(
    new window.CustomEvent("messenger:open-conversation", {
      detail: conversationId,
    }),
  );
}
// The service worker deduplicates live and push deliveries across all tabs using IndexedDB.
export async function notify(
  title: string,
  body: string,
  tag: string,
  conversationId?: string,
  userId?: string,
  mentioned = false,
) {
  if (notificationState() !== "granted") return;
  try {
    const registration = await worker();
    if (userId && desiredAccount !== userId) return;
    if (registration?.active && userId) {
      await workerMessage(registration.active, {
        type: "show-notification",
        notification: { title, body, tag, conversationId, userId, mentioned },
      });
      return;
    }
  } catch {
    /* Fall back for browsers without usable service workers. */
  }
  try {
    const shown = new window.Notification(title, { body, tag });
    shown.onclick = () => {
      window.focus();
      if (conversationId) openNotificationConversation(conversationId);
      shown.close();
    };
  } catch {
    throw new Error("Notification delivery unavailable");
  }
}
