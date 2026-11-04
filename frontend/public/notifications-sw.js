// Dedicated notification worker: no offline caching or API response caching.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) =>
  event.waitUntil(self.clients.claim()),
);
function database() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("messenger-notifications", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("state");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
async function owner(value) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(
      "state",
      value === undefined ? "readonly" : "readwrite",
    );
    const store = transaction.objectStore("state");
    const request =
      value === undefined ? store.get("owner") : store.put(value, "owner");
    transaction.oncomplete = () => {
      db.close();
      resolve(request.result);
    };
    transaction.onerror = () => {
      db.close();
      reject(transaction.error);
    };
  });
}
async function claim(key) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction("state", "readwrite");
    const store = transaction.objectStore("state");
    let fresh = false;
    const request = store.get(key);
    request.onsuccess = () => {
      if (!request.result) {
        store.put(Date.now(), key);
        fresh = true;
      }
    };
    transaction.oncomplete = () => {
      db.close();
      resolve(fresh);
    };
    transaction.onerror = () => {
      db.close();
      reject(transaction.error);
    };
  });
}
async function release(key) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction("state", "readwrite");
    transaction.objectStore("state").delete(key);
    transaction.oncomplete = () => {
      db.close();
      resolve();
    };
    transaction.onerror = () => {
      db.close();
      reject(transaction.error);
    };
  });
}
async function prune() {
  const db = await database();
  return new Promise((resolve) => {
    const transaction = db.transaction("state", "readwrite");
    const cursor = transaction.objectStore("state").openCursor();
    cursor.onsuccess = () => {
      const row = cursor.result;
      if (!row) return;
      if (row.key !== "owner" && row.value < Date.now() - 7 * 86400000)
        row.delete();
      row.continue();
    };
    transaction.oncomplete = () => {
      db.close();
      resolve();
    };
  });
}
function context(client) {
  return new Promise((resolve) => {
    const channel = new MessageChannel();
    const timer = setTimeout(() => {
      channel.port1.close();
      resolve(null);
    }, 500);
    channel.port1.onmessage = (event) => {
      clearTimeout(timer);
      channel.port1.close();
      resolve(event.data);
    };
    client.postMessage({ type: "notification-context" }, [channel.port2]);
  });
}
async function show(notification) {
  if (
    !notification ||
    !notification.userId ||
    !notification.tag ||
    (await owner()) !== notification.userId
  )
    return;
  if (!(await claim(`${notification.userId}:${notification.tag}`))) return;
  const windows = await self.clients.matchAll({
    type: "window",
    includeUncontrolled: true,
  });
  for (const client of windows.filter((window) => window.focused)) {
    const current = await context(client);
    if (
      current?.userId === notification.userId &&
      current.watching &&
      current.conversationId === notification.conversationId &&
      !notification.mentioned
    )
      return;
  }
  try {
    await self.registration.showNotification(notification.title, {
      body: notification.body,
      tag: `${notification.userId}:${notification.tag}`,
      data: {
        userId: notification.userId,
        conversationId: notification.conversationId,
      },
    });
  } catch (error) {
    await release(`${notification.userId}:${notification.tag}`);
    throw error;
  }
}
self.addEventListener("message", (event) => {
  let work;
  if (event.data?.type === "notification-owner")
    work = owner(event.data.userId).then(prune);
  if (event.data?.type === "show-notification")
    work = show(event.data.notification);
  if (work)
    event.waitUntil(
      work.then(
        () => event.ports?.[0]?.postMessage({ ok: true }),
        () => event.ports?.[0]?.postMessage({ ok: false }),
      ),
    );
});
self.addEventListener("push", (event) => {
  if (event.data) event.waitUntil(show(event.data.json()));
});
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const data = event.notification.data;
  event.waitUntil(
    (async () => {
      if (
        !data ||
        (await owner()) !== data.userId ||
        !/^[0-9a-f-]{36}$/.test(data.conversationId)
      )
        return;
      const url = `/chat?conversation=${encodeURIComponent(data.conversationId)}`;
      const windows = await self.clients.matchAll({
        type: "window",
        includeUncontrolled: true,
      });
      const existing = windows.find(
        (client) => new URL(client.url).origin === self.location.origin,
      );
      if (existing) {
        if (new URL(existing.url).pathname === "/chat")
          existing.postMessage({ type: "open-conversation", ...data });
        else await existing.navigate(url);
        await existing.focus();
      } else await self.clients.openWindow(url);
    })(),
  );
});
