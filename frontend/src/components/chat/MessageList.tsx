import FileAttachment from "./FileAttachment";
import MessageActions from "./MessageActions";
import type { MessageHandlers } from "./MessageActions";
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import {
  messageFiles,
  quoteText,
  senderColor,
  splitMentions,
  timeLabel,
} from "./chatState";
import type { ChatMessage, Conversation } from "../../types/chat";
// Describe how far one of the user's own messages has got.
function receipt(c: Conversation, m: ChatMessage) {
  const sequence = m.sequence ?? 0;
  if (sequence && sequence <= (c.readSequence ?? 0)) return "seen";
  if (sequence && sequence <= (c.deliveredSequence ?? 0)) return "delivered";
  return "sent";
}
const receiptLabels = { sent: "Sent", delivered: "Delivered", seen: "Seen" };
// Render conversation messages and scroll to the newest one.
export default function MessageList({
  conversation: c,
  userId,
  showTimestamps,
  forwardTargets,
  handlers,
  username,
  onVisible,
  hasOlder,
  loadingOlder,
  onOlder,
  jumpTo,
}: {
  conversation: Conversation;
  userId: string;
  showTimestamps: boolean;
  forwardTargets: { id: string; name: string }[];
  handlers: MessageHandlers;
  // Group members whose "@Name" mentions are highlighted; the user's own stand out.
  // The user's own username, so mentions of them stand out.
  username?: string;
  onVisible: (id: string, sequence: number) => void;
  hasOlder?: boolean;
  loadingOlder?: boolean;
  onOlder?: () => void;
  jumpTo?: { id: string; sequence: number; request: number } | null;
}) {
  const list = useRef<HTMLDivElement>(null);
  const nearBottom = useRef(true);
  const previous = useRef<{
    first?: string;
    last?: string;
    height: number;
    sequence: number;
  } | null>(null);
  const [newMessages, setNewMessages] = useState(0);
  const handledJump = useRef<number | null>(null);
  const [highlighted, setHighlighted] = useState<string | null>(null);
  const scrollPosition = useRef(0);
  const latestId = c.messages.at(-1)?.id;
  const firstId = c.messages[0]?.id;

  const reportVisible = useCallback(() => {
    const root = list.current;
    if (!root || document.visibilityState === "hidden" || !document.hasFocus())
      return;
    if (jumpTo && handledJump.current !== jumpTo.request) return;
    const viewport = root.getBoundingClientRect();
    if (viewport.height <= 0) return;
    let sequence = 0;
    for (const message of root.querySelectorAll<HTMLElement>(
      "[data-sequence]",
    )) {
      const bounds = message.getBoundingClientRect();
      // The bottom of the message must have reached the viewport, including tall attachments.
      if (
        bounds.height > 0 &&
        bounds.bottom > viewport.top &&
        bounds.bottom <= viewport.bottom + 1
      )
        sequence = Math.max(sequence, Number(message.dataset.sequence) || 0);
    }
    if (sequence) onVisible(c.id, sequence);
  }, [c.id, onVisible, jumpTo]);

  useLayoutEffect(() => {
    const root = list.current;
    if (!root) return;
    const before = previous.current;
    const target =
      jumpTo && handledJump.current !== jumpTo.request
        ? root.querySelector<HTMLElement>(`[data-message-id="${jumpTo.id}"]`)
        : null;
    if (jumpTo && handledJump.current !== jumpTo.request) {
      nearBottom.current = false;
      if (target) {
        root.scrollTop +=
          target.getBoundingClientRect().top -
          root.getBoundingClientRect().top -
          (root.clientHeight - target.getBoundingClientRect().height) / 2;
        handledJump.current = jumpTo.request;
        target.focus({ preventScroll: true });
        setHighlighted(jumpTo.id);
      } else if (before && before.first !== firstId) {
        root.scrollTop += root.scrollHeight - before.height;
      }
    } else if (!before || nearBottom.current) {
      root.scrollTop = root.scrollHeight;
    } else if (before.first !== firstId) {
      root.scrollTop += root.scrollHeight - before.height;
    }
    if (before && !nearBottom.current) {
      const incoming = c.messages.filter(
        (m) => (m.sequence ?? 0) > before.sequence && m.senderId !== userId,
      ).length;
      if (incoming) setNewMessages((count) => count + incoming);
    }
    scrollPosition.current = root.scrollTop;
    previous.current = {
      first: firstId,
      last: latestId,
      height: root.scrollHeight,
      sequence: c.messages.at(-1)?.sequence ?? 0,
    };
    reportVisible();
  }, [c.messages, firstId, latestId, reportVisible, jumpTo, userId]);

  useEffect(() => {
    if (!highlighted) return;
    const timer = window.setTimeout(() => setHighlighted(null), 2500);
    return () => window.clearTimeout(timer);
  }, [highlighted, jumpTo]);

  useEffect(() => {
    const root = list.current;
    if (!root) return;
    const check = () => reportVisible();
    const resize = () => {
      if (nearBottom.current) root.scrollTop = root.scrollHeight;
      if (previous.current) previous.current.height = root.scrollHeight;
      check();
    };
    const observer =
      typeof ResizeObserver === "undefined" ? null : new ResizeObserver(resize);
    observer?.observe(root);
    for (const child of root.children) observer?.observe(child);
    root.addEventListener("load", resize, true);
    window.addEventListener("focus", check);
    document.addEventListener("visibilitychange", check);
    const timer = window.setInterval(check, 3000);
    return () => {
      observer?.disconnect();
      root.removeEventListener("load", resize, true);
      window.removeEventListener("focus", check);
      document.removeEventListener("visibilitychange", check);
      window.clearInterval(timer);
    };
  }, [c.messages, reportVisible]);
  return (
    <div className="message-timeline">
      <div
        className="chat-messages"
        ref={list}
        style={{ overflowAnchor: "none" }}
        onScroll={() => {
          const root = list.current!;
          const movingUp = root.scrollTop < scrollPosition.current;
          scrollPosition.current = root.scrollTop;
          if (
            movingUp &&
            root.scrollTop <= 80 &&
            hasOlder &&
            !loadingOlder &&
            onOlder
          )
            onOlder();
          nearBottom.current =
            root.scrollHeight - root.scrollTop - root.clientHeight <= 48;
          if (nearBottom.current) setNewMessages(0);
          reportVisible();
        }}
        role="log"
        aria-label={`Messages with ${c.name}`}
        aria-live="polite"
      >
        {loadingOlder && (
          <div className="history-loading" role="status">
            Loading earlier messages…
          </div>
        )}
        {!c.messages.length && (
          <div className="empty-conversation">
            <h2>Say hello to {c.name}</h2>
            <p>This is the beginning of your conversation.</p>
          </div>
        )}
        {c.messages.map((m) => {
          const own = m.senderId === userId;
          const context = !m.deleted && (m.forwarded || m.replyTo);
          return (
            <article
              key={m.id}
              data-sequence={m.sequence}
              data-message-id={m.id}
              tabIndex={-1}
              className={`chat-message ${c.type === "DIRECT" ? "direct-message" : ""} ${own ? "sent" : "received"} ${context ? "has-context" : ""} ${m.deleted ? "deleted" : ""} ${highlighted === m.id ? "search-highlight" : ""}`}
            >
              {c.type === "GROUP" && (
                <strong
                  className="message-sender"
                  style={own ? undefined : { color: senderColor(m.senderId) }}
                >
                  {own ? "You" : m.senderName}
                </strong>
              )}
              {context && (
                <div className="message-context">
                  {m.forwarded && <small>Forwarded</small>}
                  {m.replyTo && (
                    <blockquote className="message-reply">
                      <strong>{m.replyTo.senderName}</strong>
                      <span>{quoteText(m.replyTo)}</span>
                    </blockquote>
                  )}
                </div>
              )}
              {m.deleted ? (
                <p className="deleted-text">This message was deleted</p>
              ) : (
                m.text && (
                  <p>
                    {splitMentions(m.text, m.mentions ?? []).map(
                      (part, index) =>
                        part.mention ? (
                          <span
                            key={index}
                            className={`mention ${part.mention.username === username ? "mention-me" : ""}`}
                            title={`@${part.mention.username}`}
                          >
                            {part.text}
                          </span>
                        ) : (
                          part.text
                        ),
                    )}
                  </p>
                )
              )}
              {messageFiles(m).length > 0 && (
                <div className="message-attachments">
                  {messageFiles(m).map((file) => (
                    <FileAttachment key={file.id} attachment={file} />
                  ))}
                </div>
              )}
              {!!m.reactions?.length && (
                <div className="message-reactions">
                  {m.reactions.map((r) => (
                    <button
                      key={r.emoji}
                      className={r.mine ? "mine" : ""}
                      aria-pressed={r.mine}
                      aria-label={`${r.emoji} ${r.count}${r.mine ? ", including you" : ""}`}
                      onClick={() =>
                        handlers.onReact(m, r.mine ? null : r.emoji)
                      }
                    >
                      {r.emoji} {r.count}
                    </button>
                  ))}
                </div>
              )}
              {showTimestamps && (
                <time
                  dateTime={m.sentAt}
                  title={new Date(m.sentAt).toLocaleString()}
                >
                  {timeLabel(m.sentAt)}
                  {m.edited && !m.deleted && " · Edited"}
                  {own && !m.deleted && (
                    <span
                      className={`receipt ${receipt(c, m)}`}
                      role="img"
                      aria-label={receiptLabels[receipt(c, m)]}
                      title={receiptLabels[receipt(c, m)]}
                    >
                      {receipt(c, m) === "sent" ? "✓" : "✓✓"}
                    </span>
                  )}
                </time>
              )}
              <MessageActions
                message={m}
                own={own}
                forwardTargets={forwardTargets}
                handlers={handlers}
              />
            </article>
          );
        })}
      </div>
      {newMessages > 0 && (
        <button
          className="jump-to-latest"
          aria-label="New messages, scroll to latest"
          onClick={() => {
            const root = list.current!;
            root.scrollTop = root.scrollHeight;
            nearBottom.current = true;
            setNewMessages(0);
            reportVisible();
          }}
        >
          <span className="new-message-count">{newMessages}</span>
          <span>New messages</span>
          <span className="jump-arrow" aria-hidden="true">
            ↓
          </span>
        </button>
      )}
    </div>
  );
}
