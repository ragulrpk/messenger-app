import { useEffect, useRef, useState } from "react";
import { listSharedFiles } from "../../services/chatService";
import type { ChatMessage } from "../../types/chat";
import FileAttachment from "./FileAttachment";
import { messageFiles } from "./chatState";

export default function SharedFiles({
  conversationId,
  name,
  onClose,
  onSelect,
}: {
  conversationId: string;
  name: string;
  onClose: () => void;
  onSelect: (message: ChatMessage) => void;
}) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const active = useRef(false);
  const busy = useRef(false);

  useEffect(() => {
    active.current = true;
    let current = true;
    listSharedFiles(conversationId)
      .then((page) => {
        if (!current) return;
        setMessages(page.messages);
        setHasMore(page.hasMore);
        setLoaded(true);
        setError("");
      })
      .catch((failure) => {
        if (current) setError((failure as Error).message);
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
      active.current = false;
    };
  }, [conversationId, retry]);

  async function more() {
    if (busy.current) return;
    busy.current = true;
    setLoading(true);
    setError("");
    try {
      const page = await listSharedFiles(
        conversationId,
        messages.at(-1)?.sequence,
      );
      if (active.current) {
        setMessages((previous) => [
          ...new Map(
            [...previous, ...page.messages].map((message) => [
              message.id,
              message,
            ]),
          ).values(),
        ]);
        setHasMore(page.hasMore);
      }
    } catch (failure) {
      if (active.current) setError((failure as Error).message);
    } finally {
      busy.current = false;
      if (active.current) setLoading(false);
    }
  }

  return (
    <section
      className="message-search shared-files"
      aria-label={`Shared files in ${name}`}
      onKeyDown={(event) => {
        if (event.key === "Escape") onClose();
      }}
    >
      <div className="shared-files-heading">
        <div className="message-search-heading">
          <strong>Shared files</strong>
          <small>{name}</small>
        </div>
        <button
          autoFocus
          className="icon-button"
          aria-label="Close shared files"
          onClick={onClose}
        >
          ×
        </button>
      </div>
      <div className="message-search-results" aria-busy={loading}>
        {loaded && !messages.length && (
          <p role="status">No shared files yet.</p>
        )}
        {messages.map((message) => (
          <article className="shared-file-row" key={message.id}>
            <strong>{message.senderName}</strong>
            <time dateTime={message.sentAt}>
              {new Date(message.sentAt).toLocaleString()}
            </time>
            {messageFiles(message).map((file) => (
              <FileAttachment key={file.id} attachment={file} />
            ))}
            <button
              className="shared-file-context"
              onClick={() => onSelect(message)}
            >
              View in chat
            </button>
          </article>
        ))}
        {loading && <p role="status">Loading files…</p>}
        {error && (
          <p role="alert">
            {error}{" "}
            <button
              onClick={() =>
                loaded
                  ? void more()
                  : (setLoading(true), setRetry((value) => value + 1))
              }
            >
              Retry files
            </button>
          </p>
        )}
        {hasMore && !error && (
          <button
            className="history-more"
            disabled={loading}
            onClick={() => void more()}
          >
            Load more files
          </button>
        )}
      </div>
    </section>
  );
}
