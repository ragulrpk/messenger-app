import { useCallback, useEffect, useRef, useState } from "react";
import {
  getMessages,
  listConversations,
  openDirect,
  sendMessage,
} from "../../services/chatService";
import type { ConversationSummary } from "../../services/chatService";
import type { ChatMessage, Conversation } from "../../types/chat";
import { connectChatEvents } from "../../services/chatEvents";

// Deduplicate messages by ID and sort them in server sequence order.
export function mergeMessages(
  previous: ChatMessage[],
  incoming: ChatMessage[],
) {
  return [
    ...new Map([...previous, ...incoming].map((m) => [m.id, m])).values(),
  ].sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0));
}
// Add local display state to a server conversation summary.
function summary(value: ConversationSummary): Conversation {
  return {
    ...value,
    type: "DIRECT",
    pinned: false,
    muted: false,
    hidden: false,
    unread: 0,
    messages: [],
  };
}
// Manage conversation lists, message history, polling, and sends.
export default function useMessaging(
  selectedId: string | null,
  onInitialConversation?: (id: string) => void,
) {
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
  const sendingIds = useRef(new Set<string>());
  const attempts = useRef<Record<string, { clientId: string; text: string }>>(
    {},
  );
  const listBusy = useRef(false);
  const listQueued = useRef(false);
  const refreshHistory = useRef<(() => void) | null>(null);
  const pagedBeyondFirst = useRef(false);
  const initializedSelection = useRef(false);
  const olderBusy = useRef(new Set<string>());

  // Fetch conversation summaries without overwriting newer local messages.
  const refreshList = useCallback(
    async (cursor?: string) => {
      if (listBusy.current) {
        if (!cursor) listQueued.current = true;
        return;
      }
      listBusy.current = true;
      try {
        const page = await listConversations(cursor);
        if (!alive.current) return;
        setConversations((previous) => {
          const map = new Map(previous.map((c) => [c.id, c]));
          for (const row of page.conversations) {
            const existing = map.get(row.id);
            // A slower list response must not replace a newer send acknowledgement.
            const latest = existing?.lastMessage;
            const useExisting =
              (latest?.sequence ?? 0) > (row.lastMessage?.sequence ?? 0);
            map.set(
              row.id,
              existing
                ? {
                    ...existing,
                    name: row.name,
                    updatedAt: useExisting ? existing.updatedAt : row.updatedAt,
                    lastMessage: useExisting ? latest : row.lastMessage,
                  }
                : summary(row),
            );
          }
          return [...map.values()];
        });
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
    [onInitialConversation],
  );

  useEffect(() => {
    alive.current = true;
    void refreshList();
    const timer = window.setInterval(() => {
      if (document.visibilityState !== "hidden") void refreshList();
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
    // 2026-09-23: One socket per mounted account; polling remains a reconciliation fallback.
    return connectChatEvents(() => {
      void refreshList();
      refreshHistory.current?.();
    });
  }, [refreshList]);

  useEffect(() => {
    if (!selectedId) return;
    const id = selectedId;
    let active = true;
    let busy = false;
    let queued = false;
    let continuation: number | undefined;
    // Load new messages for the selected conversation.
    async function refresh() {
      if (!active) return;
      if (busy) {
        queued = true;
        return;
      }
      busy = true;
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
        let page = await getMessages(
          id,
          cursor ? { after: cursor.last } : undefined,
        );
        if (!active) return;
        cursors.current[id] = {
          first: cursors.current[id]?.first || page.messages[0]?.sequence || 0,
          last: page.messages.at(-1)?.sequence ?? cursor?.last ?? 0,
        };
        const initialMessages = page.messages;
        const initialHasMore = page.hasMore;
        setConversations((previous) =>
          previous.map((c) =>
            c.id === id
              ? { ...c, messages: mergeMessages(c.messages, initialMessages) }
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
          for (let batch = 0; active && page.hasMore && batch < 20; batch++) {
            const after = cursors.current[id].last;
            page = await getMessages(id, { after });
            if (!active) return;
            const last = page.messages.at(-1)?.sequence;
            if (last === undefined || last <= after) {
              page = { ...page, hasMore: false };
              break;
            }
            cursors.current[id].last = last;
            const batchMessages = page.messages;
            setConversations((previous) =>
              previous.map((c) =>
                c.id === id
                  ? { ...c, messages: mergeMessages(c.messages, batchMessages) }
                  : c,
              ),
            );
          }
          if (page.hasMore) queued = true;
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
      if (document.visibilityState !== "hidden") void refresh();
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

  // Create or reveal a direct conversation with a person.
  async function open(userId: string) {
    const row = await openDirect(userId);
    if (!alive.current) return null;
    setConversations((previous) =>
      previous.some((c) => c.id === row.id)
        ? previous.map((c) =>
            c.id === row.id ? { ...c, hidden: false, unread: 0 } : c,
          )
        : [...previous, summary(row)],
    );
    return row.id;
  }
  // Load the next page of older messages for a conversation.
  async function older(id: string) {
    const cursor = cursors.current[id];
    if (!cursor?.first || olderBusy.current.has(id)) return;
    olderBusy.current.add(id);
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
            ? { ...c, messages: mergeMessages(page.messages, c.messages) }
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
    } finally {
      olderBusy.current.delete(id);
    }
  }
  // Send a message with a stable retry ID and update the conversation.
  async function send(id: string, text: string) {
    if (sendingIds.current.has(id)) return false;
    sendingIds.current.add(id);
    if (attempts.current[id]?.text !== text)
      attempts.current[id] = { text, clientId: crypto.randomUUID() };
    setSending((previous) => ({ ...previous, [id]: true }));
    setSendErrors((previous) => ({ ...previous, [id]: "" }));
    try {
      const message = await sendMessage(
        id,
        attempts.current[id].clientId,
        text,
      );
      if (!alive.current) return false;
      setConversations((previous) =>
        previous.map((c) =>
          c.id === id
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
    conversations,
    setConversations,
    listLoading,
    listError,
    nextCursor,
    refreshList,
    history,
    older,
    open,
    send,
    sending,
    sendErrors,
  };
}
