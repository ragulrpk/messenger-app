import FileAttachment from "./FileAttachment";
import { useEffect, useRef } from "react";
import { timeLabel } from "./chatState";
import type { Conversation } from "../../types/chat";
// Render conversation messages and scroll to the newest one.
export default function MessageList({
  conversation: c,
  userId,
  showTimestamps,
}: {
  conversation: Conversation;
  userId: string;
  showTimestamps: boolean;
}) {
  const end = useRef<HTMLDivElement>(null);
  const latestId = c.messages.at(-1)?.id;
  useEffect(() => {
    end.current?.scrollIntoView({ block: "end" });
  }, [c.id, latestId]);
  return (
    <div
      className="chat-messages"
      role="log"
      aria-label={`Messages with ${c.name}`}
      aria-live="polite"
    >
      {!c.messages.length && (
        <div className="empty-conversation">
          <h2>Say hello to {c.name}</h2>
          <p>This is the beginning of your conversation.</p>
        </div>
      )}
      {c.messages.map((m) => (
        <article
          key={m.id}
          className={`chat-message ${c.type === "DIRECT" ? "direct-message" : ""} ${m.senderId === userId ? "sent" : "received"}`}
        >
          {c.type === "GROUP" && (
            <strong>{m.senderId === userId ? "You" : m.senderName}</strong>
          )}
          {m.text && <p>{m.text}</p>}
          {m.attachments?.length ? (
            <div className="message-attachments">
              {m.attachments.map((file, index) => (
                <FileAttachment key={index} file={file} />
              ))}
            </div>
          ) : null}
          {showTimestamps && (
            <time
              dateTime={m.sentAt}
              title={new Date(m.sentAt).toLocaleString()}
            >
              {timeLabel(m.sentAt)}
            </time>
          )}
        </article>
      ))}
      <div ref={end} />
    </div>
  );
}
