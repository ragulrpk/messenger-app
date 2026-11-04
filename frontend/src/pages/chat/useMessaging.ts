import { useCallback, useEffect, useRef, useState } from "react";
import {
  createGroup,
  clearChat,
  deleteMessage,
  editMessage,
  leaveGroup,
  forwardMessage,
  getMessages,
  listConversations,
  markDelivered,
  markRead,
  openDirect,
  reactToMessage,
  sendFiles,
  sendMessage,
  updatePreferences,
} from "../../services/chatService";
import type { ConversationSummary } from "../../services/chatService";
import type {
  ChatMessage,
  Conversation,
  ConversationAction,
} from "../../types/chat";
import { connectChatEvents } from "../../services/chatEvents";
import { connectNotifications } from "../../services/notificationFeed";
import { applyAction } from "../../components/chat/chatState";
import useChatTyping from "./useChatTyping";

// Deduplicate messages by ID and sort them in server sequence order.
export function mergeMessages(
  previous: ChatMessage[],
  incoming: ChatMessage[],
) {
  return [
    ...new Map([...previous, ...incoming].map((m) => [m.id, m])).values(),
  ].sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0));
}
// Replace messages that are already loaded and drop removed ones; changes to
// messages outside the loaded window arrive with them when they are paged in.
function applyChanges(
  messages: ChatMessage[],
  changed: ChatMessage[],
  removed: string[],
) {
  const updates = new Map(changed.map((m) => [m.id, m]));
  const gone = new Set(removed);
  return messages
    .filter((m) => !gone.has(m.id))
    .map((m) => updates.get(m.id) ?? m);
}
// Keep a local preview newer than the server row's only when the server had not
// seen it yet, such as a send acknowledged after the list was read. The row's
// updatedAt already covering it means it is hidden from this user now (deleted
// for them, perhaps on another device), so the row's older preview wins.
export function keepLocalPreview(
  local: ChatMessage | null | undefined,
  row: ConversationSummary,
) {
  return (
    !!local &&
    (local.sequence ?? 0) > (row.lastMessage?.sequence ?? 0) &&
    Date.parse(local.sentAt) > Date.parse(row.updatedAt)
  );
}
// The preview after messages change: an edit replaces it, and when it is
// removed the newest remaining loaded message takes its place (null until the
// next list refresh fills it in, if none is loaded).
export function previewAfter(
  preview: ChatMessage | null | undefined,
  messages: ChatMessage[],
  changed: ChatMessage | null,
  removedId?: string,
) {
  if (changed && preview?.id === changed.id) return changed;
  if (removedId && preview?.id === removedId) return messages.at(-1) ?? null;
  return preview;
}
// Conversations whose newest message, from someone else (it is unread), has not
// been reported as delivered yet. Polls that show nothing new report nothing.
export function newArrivals(
  rows: ConversationSummary[],
  reported: Record<string, number>,
) {
  return rows.filter(
    (row) =>
      row.unread > 0 &&
      (row.lastMessage?.sequence ?? 0) > (reported[row.id] ?? 0),
  );
}
// Pick the saved preferences, unread count, and contact status from a server row.
function saved(value: ConversationSummary) {
  return {
    historyFromSequence: value.historyFromSequence ?? 0,
    type: value.type ?? (value.targetId === null ? "GROUP" : "DIRECT"),
    pinned: value.pinned,
    muted: value.muted,
    hidden: value.hidden,
    unread: value.unread,
    // Groups have no single other person, so no status or note.
    status: value.targetStatus ?? undefined,
    note: value.targetNote ?? undefined,
    deliveredSequence: value.targetDeliveredSequence,
    readSequence: value.targetReadSequence,
    role: value.role,
    memberCount: value.memberCount,
    adminsOnly: value.adminsOnly,
    archived: value.archived === true,
  };
}
// Add local display state to a server conversation summary.
function summary(value: ConversationSummary): Conversation {
  return {
    ...value,
    ...saved(value),
    messages: [],
  };
}
// The user is looking at the page only when its tab is visible and its window has
// focus; a window behind another app is still "visible", but nobody is reading it.
function watching() {
  return document.visibilityState !== "hidden" && document.hasFocus();
}
// Manage conversation lists, message history, polling, and sends.
export default function useMessaging(
  selectedId: string | null,
  onInitialConversation?: (id: string) => void,
  userId?: string,
) {
  const { isTyping, receiveTyping, updateTyping } = useChatTyping(selectedId);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState("");
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [history, setHistory] = useState<
    Record<
      string,
      { loaded: boolean; older: boolean; loading: boolean; error: string }
    >
  >({});
  const [sending, setSending] = useState<Record<string, boolean>>({});
  const [sendErrors, setSendErrors] = useState<Record<string, string>>({});
  const alive = useRef(false);
  const cursors = useRef<Record<string, { first: number; last: number }>>({});
  // Last change revision applied per conversation, for edits, deletes and reactions.
  const revisions = useRef<Record<string, number>>({});
  const sendingIds = useRef(new Set<string>());
  const attempts = useRef<
    Record<string, { clientId: string; text: string; files: File[] }>
  >({});
  const listBusy = useRef(false);
  // While live hints arrive, polling is only a slow safety net.
  const live = useRef(false);
  const lastListRefresh = useRef(0);
  const listQueued = useRef(false);
  const refreshHistory = useRef<(() => void) | null>(null);
  const pagedBeyondFirst = useRef(false);
  const initializedSelection = useRef(false);
  const olderBusy = useRef(new Map<string, Promise<void>>());
  const [actionError, setActionError] = useState("");
  const selectedRef = useRef(selectedId);
  const notificationRefresh = useRef<(() => void) | null>(null);
  const notificationLive = useRef<((value: boolean) => void) | null>(null);
  // Conversations the server has listed, so missing ones can be told apart from new local ones.
  const listedBefore = useRef(new Set<string>());
  // Newest sequence per conversation already reported as delivered.
  const deliveredUpTo = useRef<Record<string, number>>({});
  const deliveryBusy = useRef(false);
  // Report new arrivals once; a failure leaves them unreported for the next list load.
  const reportDelivered = useCallback(async (rows: ConversationSummary[]) => {
    const arrived = newArrivals(rows, deliveredUpTo.current);
    if (!arrived.length || deliveryBusy.current) return;
    deliveryBusy.current = true;
    try {
      await markDelivered();
      for (const row of arrived)
        deliveredUpTo.current[row.id] = row.lastMessage?.sequence ?? 0;
    } catch {
      // Delivery ticks are best effort; the next list load retries.
    } finally {
      deliveryBusy.current = false;
    }
  }, []);

  const readPositions = useRef<
    Record<string, { reported: number; busy: boolean }>
  >({});
  const readVisible = useCallback(
    async (id: string, sequence: number) => {
      if (!alive.current || selectedId !== id || !watching() || sequence <= 0)
        return;
      const position = (readPositions.current[id] ??= {
        reported: 0,
        busy: false,
      });
      if (position.busy || sequence <= position.reported) return;
      position.busy = true;
      try {
        const row = await markRead(id, sequence);
        position.reported = sequence;
        if (alive.current)
          setConversations((previous) =>
            previous.map((c) =>
              c.id === id ? { ...c, unread: row.unread } : c,
            ),
          );
      } catch {
        // Retry on the next visibility check.
      } finally {
        position.busy = false;
      }
    },
    [selectedId],
  );

  useEffect(() => {
    selectedRef.current = selectedId;
  }, [selectedId]);

  // Fetch conversation summaries without overwriting newer local messages.
  const refreshList = useCallback(
    async (cursor?: string) => {
      if (listBusy.current) {
        if (!cursor) listQueued.current = true;
        return;
      }
      listBusy.current = true;
      lastListRefresh.current = Date.now();
      try {
        const page = await listConversations(cursor);
        if (!alive.current) return;
        setConversations((previous) => {
          const map = new Map(previous.map((c) => [c.id, c]));
          for (const row of page.conversations) {
            const existing = map.get(row.id);
            // A slower list response must not replace a newer send acknowledgement.
            const latest = existing?.lastMessage;
            const useExisting = keepLocalPreview(latest, row);
            const merged = existing
              ? {
                  ...existing,
                  ...saved(row),
                  name: row.name,
                  updatedAt: useExisting ? existing.updatedAt : row.updatedAt,
                  lastMessage: useExisting ? latest : row.lastMessage,
                }
              : summary(row);
            const floor = Math.max(
              existing?.historyFromSequence ?? 0,
              row.historyFromSequence ?? 0,
            );
            if (floor > (existing?.historyFromSequence ?? 0)) {
              const cursor = cursors.current[row.id];
              if (cursor && cursor.first <= floor) {
                cursors.current[row.id] = {
                  first: floor + 1,
                  last: Math.max(cursor.last, floor),
                };
                // Older pages cannot contain anything visible below the cleared position.
              }
            }
            map.set(row.id, {
              ...merged,
              historyFromSequence: floor,
              messages: merged.messages.filter(
                (m) => (m.sequence ?? 0) > floor,
              ),
              lastMessage:
                (merged.lastMessage?.sequence ?? 0) > floor
                  ? merged.lastMessage
                  : null,
            });
          }
          // Drop conversations the user left or was removed from: ones the server
          // listed before that should be on this first page but are not. A
          // conversation only created locally may be missing from an older response.
          if (!cursor) {
            const listed = new Set(page.conversations.map((row) => row.id));
            // Compare as dates: as text, "…:05Z" sorts after "…:05.120Z" though it is earlier.
            const oldest = Date.parse(
              page.conversations.at(-1)?.updatedAt ?? "",
            );
            for (const [id, c] of map)
              if (
                !listed.has(id) &&
                listedBefore.current.has(id) &&
                (!page.nextCursor || Date.parse(c.updatedAt) >= oldest)
              )
                map.delete(id);
          }
          return [...map.values()];
        });
        for (const row of page.conversations) listedBefore.current.add(row.id);
        // New messages move their conversation to the first page.
        if (!cursor) void reportDelivered(page.conversations);
        // Polling the first page does not reset a user's pagination progress.
        if (cursor) pagedBeyondFirst.current = true;
        if (cursor || !pagedBeyondFirst.current) setNextCursor(page.nextCursor);
        setListError("");
        if (!initializedSelection.current) {
          initializedSelection.current = true;
          if (page.conversations[0])
            onInitialConversation?.(page.conversations[0].id);
        }
      } catch (error) {
        if (alive.current) setListError((error as Error).message);
      } finally {
        listBusy.current = false;
        if (alive.current) setListLoading(false);
        // 2026-09-23: A live hint during a request must not be lost behind the busy guard.
        if (alive.current && listQueued.current) {
          listQueued.current = false;
          void refreshList();
        }
      }
    },
    [onInitialConversation, reportDelivered],
  );

  useEffect(() => {
    alive.current = true;
    void refreshList();
    const timer = window.setInterval(() => {
      if (document.visibilityState === "hidden") return;
      // With live hints, poll the list every 30 s instead of every 5 s.
      if (live.current && Date.now() - lastListRefresh.current < 30000) return;
      void refreshList();
    }, 5000);
    const focus = () => void refreshList();
    window.addEventListener("focus", focus);
    return () => {
      alive.current = false;
      window.clearInterval(timer);
      window.removeEventListener("focus", focus);
    };
  }, [refreshList]);

  useEffect(() => {
    if (!userId) return;
    const feed = connectNotifications(userId, () => ({
      conversationId: selectedRef.current,
      watching: watching(),
    }));
    notificationRefresh.current = () => void feed.refresh();
    notificationLive.current = feed.setLive;
    feed.setLive(live.current);
    return () => {
      notificationRefresh.current = null;
      notificationLive.current = null;
      feed.close();
    };
  }, [userId]);

  useEffect(() => {
    // 2026-09-23: One socket per mounted account; polling remains a reconciliation fallback.
    return connectChatEvents(
      () => {
        void refreshList();
        notificationRefresh.current?.();
        refreshHistory.current?.();
      },
      receiveTyping,
      (value) => {
        live.current = value;
        notificationLive.current?.(value);
      },
    );
  }, [refreshList, receiveTyping]);

  useEffect(() => {
    if (!selectedId) return;
    const id = selectedId;
    let active = true;
    let busy = false;
    let queued = false;
    let continuation: number | undefined;
    let lastRefresh = 0;
    // Load new messages for the selected conversation.
    async function refresh() {
      if (!active) return;
      if (busy) {
        queued = true;
        return;
      }
      busy = true;
      lastRefresh = Date.now();
      const cursor = cursors.current[id];
      setHistory((previous) => ({
        ...previous,
        [id]: {
          loaded: !!cursor,
          older: previous[id]?.older ?? false,
          loading: !cursor,
          error: "",
        },
      }));
      try {
        const revision = revisions.current[id];
        let page = await getMessages(
          id,
          cursor ? { after: cursor.last, changedAfter: revision } : undefined,
        );
        if (!active) return;
        cursors.current[id] = {
          first: cursors.current[id]?.first || page.messages[0]?.sequence || 0,
          last: page.messages.at(-1)?.sequence ?? cursor?.last ?? 0,
        };
        revisions.current[id] = Math.max(revision ?? 0, page.revision ?? 0);
        const initialMessages = page.messages;
        const initialHasMore = page.hasMore;
        const changed = page.changed ?? [];
        const removed = page.removed ?? [];
        setConversations((previous) =>
          previous.map((c) =>
            c.id === id
              ? {
                  ...c,
                  messages: applyChanges(
                    mergeMessages(c.messages, initialMessages),
                    changed,
                    removed,
                  ).filter(
                    (m) => (m.sequence ?? 0) > (c.historyFromSequence ?? 0),
                  ),
                }
              : c,
          ),
        );
        setHistory((previous) => ({
          ...previous,
          [id]: {
            loaded: true,
            loading: false,
            error: "",
            older: cursor ? (previous[id]?.older ?? false) : initialHasMore,
          },
        }));
        // 2026-09-23: Drain missed forward pages promptly, yielding after 20 pages for fairness.
        if (cursor) {
          for (
            let batch = 0;
            active && (page.hasMore || page.hasMoreChanges) && batch < 20;
            batch++
          ) {
            const after = cursors.current[id].last;
            const changedAfter = revisions.current[id] ?? 0;
            page = await getMessages(id, { after, changedAfter });
            if (!active) return;
            const last = page.messages.at(-1)?.sequence ?? after;
            const nextRevision = page.revision ?? changedAfter;
            if (
              (page.hasMore || page.hasMoreChanges) &&
              last <= after &&
              nextRevision <= changedAfter
            )
              throw new Error(
                "Message synchronization did not advance. Retrying automatically.",
              );
            const batchMessages = page.messages;
            const changed = page.changed ?? [];
            const removed = page.removed ?? [];
            setConversations((previous) =>
              previous.map((c) =>
                c.id === id
                  ? {
                      ...c,
                      messages: applyChanges(
                        mergeMessages(c.messages, batchMessages),
                        changed,
                        removed,
                      ).filter(
                        (m) => (m.sequence ?? 0) > (c.historyFromSequence ?? 0),
                      ),
                    }
                  : c,
              ),
            );
            cursors.current[id].last = Math.max(after, last);
            revisions.current[id] = Math.max(changedAfter, nextRevision);
          }
          if (page.hasMore || page.hasMoreChanges) queued = true;
        }
      } catch (error) {
        if (active)
          setHistory((previous) => ({
            ...previous,
            [id]: {
              loaded: !!cursor,
              older: previous[id]?.older ?? false,
              loading: false,
              error: (error as Error).message,
            },
          }));
      } finally {
        busy = false;
        if (active && queued) {
          queued = false;
          continuation = window.setTimeout(() => void refresh(), 0);
        }
      }
    }
    void refresh();
    refreshHistory.current = () => void refresh();
    const timer = window.setInterval(() => {
      if (document.visibilityState === "hidden") return;
      // With live hints, poll the open conversation every 15 s instead of every 3 s.
      if (live.current && Date.now() - lastRefresh < 15000) return;
      void refresh();
    }, 3000);
    const focus = () => void refresh();
    window.addEventListener("focus", focus);
    return () => {
      active = false;
      refreshHistory.current = null;
      window.clearTimeout(continuation);
      window.clearInterval(timer);
      window.removeEventListener("focus", focus);
    };
  }, [selectedId]);

  // Create a group and add it to the list.
  async function newGroup(
    name: string,
    memberIds: string[],
    adminsOnly: boolean,
  ) {
    const row = await createGroup(name, memberIds, adminsOnly);
    if (!alive.current) return null;
    setConversations((previous) => [
      ...previous.filter((c) => c.id !== row.id),
      summary(row),
    ]);
    return row.id;
  }
  // Leave a group; it disappears from the list straight away.
  async function leave(id: string) {
    await leaveGroup(id);
    if (alive.current)
      setConversations((previous) => previous.filter((c) => c.id !== id));
  }
  // Create or reveal a direct conversation with a person.
  async function open(userId: string) {
    const row = await openDirect(userId);
    if (!alive.current) return null;
    setConversations((previous) =>
      previous.some((c) => c.id === row.id)
        ? previous.map((c) => (c.id === row.id ? { ...c, hidden: false } : c))
        : [...previous, summary(row)],
    );
    return row.id;
  }
  // Apply a sidebar action at once and save it; on failure the list reloads the saved state.
  async function act(c: Conversation, value: ConversationAction) {
    if (value === "read" && c.unread === 0) delete readPositions.current[c.id];
    setConversations((previous) => applyAction(previous, c.id, value));
    setActionError("");
    try {
      const row =
        value === "read" && c.unread > 0
          ? await markRead(c.id)
          : await updatePreferences(
              c.id,
              value === "pin"
                ? { pinned: !c.pinned }
                : value === "mute"
                  ? { muted: !c.muted }
                  : value === "read"
                    ? { unread: true }
                    : { hidden: true },
            );
      if (!alive.current) return;
      setConversations((previous) =>
        previous.map((x) => (x.id === row.id ? { ...x, ...saved(row) } : x)),
      );
    } catch (error) {
      if (!alive.current) return;
      setActionError((error as Error).message);
      void refreshList();
    }
  }
  // Load the next page of older messages for a conversation.
  function older(id: string): Promise<void> {
    const pending = olderBusy.current.get(id);
    if (pending) return pending;
    const request = loadOlder(id).finally(() => olderBusy.current.delete(id));
    olderBusy.current.set(id, request);
    return request;
  }
  async function loadOlder(id: string) {
    const cursor = cursors.current[id];
    if (!cursor?.first) return;
    setHistory((previous) => ({
      ...previous,
      [id]: { ...previous[id], loading: true, error: "" },
    }));
    try {
      const page = await getMessages(id, { before: cursor.first });
      if (!alive.current) return;
      cursors.current[id] = {
        ...cursors.current[id],
        first: page.messages[0]?.sequence ?? cursor.first,
      };
      setConversations((previous) =>
        previous.map((c) =>
          c.id === id
            ? {
                ...c,
                messages: mergeMessages(page.messages, c.messages).filter(
                  (m) => (m.sequence ?? 0) > (c.historyFromSequence ?? 0),
                ),
              }
            : c,
        ),
      );
      setHistory((previous) => ({
        ...previous,
        [id]: { ...previous[id], loading: false, older: page.hasMore },
      }));
    } catch (error) {
      if (alive.current)
        setHistory((previous) => ({
          ...previous,
          [id]: {
            ...previous[id],
            loading: false,
            error: (error as Error).message,
          },
        }));
    }
  }
  async function clear(id: string) {
    const row = await clearChat(id);
    if (!alive.current) return;
    const floor = row.historyFromSequence ?? 0;
    cursors.current[id] = { first: floor + 1, last: floor };
    delete readPositions.current[id];
    setConversations((previous) =>
      previous.map((c) =>
        c.id === id
          ? {
              ...c,
              ...row,
              messages: c.messages.filter((m) => (m.sequence ?? 0) > floor),
            }
          : c,
      ),
    );
    setHistory((previous) => ({
      ...previous,
      [id]: { loaded: true, loading: false, older: false, error: "" },
    }));
  }
  // Keep history contiguous so live catch-up and read positions remain valid after a search jump.
  const revealVersion = useRef(0);
  async function reveal(id: string, sequence: number) {
    const request = ++revealVersion.current;
    while (
      alive.current &&
      selectedRef.current === id &&
      revealVersion.current === request
    ) {
      const first = cursors.current[id]?.first;
      if (!first || first <= sequence) return;
      await older(id);
      if (cursors.current[id]?.first === first)
        throw new Error("Unable to load that message. Please try again.");
    }
  }
  // Put a changed message into its conversation, or take it out.
  function replaceMessage(
    id: string,
    message: ChatMessage | null,
    removedId?: string,
  ) {
    setConversations((previous) =>
      previous.map((c) => {
        if (c.id !== id) return c;
        const messages = applyChanges(
          c.messages,
          message ? [message] : [],
          removedId ? [removedId] : [],
        );
        return {
          ...c,
          messages,
          lastMessage: previewAfter(
            c.lastMessage,
            messages,
            message,
            removedId,
          ),
        };
      }),
    );
  }
  // Edit, delete, react to, or forward a message; errors are returned for display.
  async function edit(id: string, messageId: string, text: string) {
    const message = await editMessage(messageId, text);
    if (alive.current) replaceMessage(id, message);
  }
  async function remove(id: string, message: ChatMessage, everyone: boolean) {
    await deleteMessage(message.id, everyone);
    if (!alive.current) return;
    if (everyone)
      replaceMessage(id, {
        ...message,
        text: "",
        attachment: null,
        attachments: [],
        reactions: [],
        deleted: true,
      });
    else replaceMessage(id, null, message.id);
  }
  async function react(id: string, messageId: string, emoji: string | null) {
    const message = await reactToMessage(messageId, emoji);
    if (alive.current) replaceMessage(id, message);
  }
  async function forward(messageId: string, targetId: string) {
    await forwardMessage(messageId, targetId, crypto.randomUUID());
    if (alive.current) void refreshList();
  }
  // Send a message, or files as one message, with a stable retry ID and update
  // the conversation.
  async function send(
    id: string,
    text: string,
    files: File[] = [],
    replyToId?: string,
  ) {
    if (sendingIds.current.has(id)) return false;
    sendingIds.current.add(id);
    const attempt = attempts.current[id];
    if (
      attempt?.text !== text ||
      attempt.files.length !== files.length ||
      attempt.files.some((f, index) => f !== files[index])
    )
      attempts.current[id] = { text, files, clientId: crypto.randomUUID() };
    setSending((previous) => ({ ...previous, [id]: true }));
    setSendErrors((previous) => ({ ...previous, [id]: "" }));
    try {
      const { clientId } = attempts.current[id];
      const message = files.length
        ? await sendFiles(id, clientId, files, text, replyToId)
        : await sendMessage(id, clientId, text, replyToId);
      if (!alive.current) return false;
      setConversations((previous) =>
        previous.map((c) =>
          c.id === id && (message.sequence ?? 0) > (c.historyFromSequence ?? 0)
            ? {
                ...c,
                messages: mergeMessages(c.messages, [message]),
                lastMessage:
                  (c.lastMessage?.sequence ?? 0) > (message.sequence ?? 0)
                    ? c.lastMessage
                    : message,
                updatedAt:
                  (c.lastMessage?.sequence ?? 0) > (message.sequence ?? 0)
                    ? c.updatedAt
                    : message.sentAt,
              }
            : c,
        ),
      );
      delete attempts.current[id];
      return true;
    } catch (error) {
      if (alive.current)
        setSendErrors((previous) => ({
          ...previous,
          [id]: (error as Error).message,
        }));
      return false;
    } finally {
      sendingIds.current.delete(id);
      if (alive.current)
        setSending((previous) => ({ ...previous, [id]: false }));
    }
  }
  return {
    readVisible,
    isTyping,
    updateTyping,
    conversations,
    setConversations,
    listLoading,
    listError,
    nextCursor,
    refreshList,
    history,
    older,
    reveal,
    clear,
    open,
    act,
    actionError,
    newGroup,
    leave,
    edit,
    remove,
    react,
    forward,
    send,
    sending,
    sendErrors,
  };
}
