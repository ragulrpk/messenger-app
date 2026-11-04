import { useEffect, useRef, useState } from "react";
import { searchMessages } from "../../services/chatService";
import type { ChatMessage } from "../../types/chat";
import { messageFiles } from "./chatState";

function highlight(text: string, term: string) {
  const match = text.toLowerCase().indexOf(term.toLowerCase());
  const startAt = Math.max(0, match - 60);
  const endAt = Math.min(text.length, Math.max(220, match + term.length + 100));
  text = `${startAt ? "…" : ""}${text.slice(startAt, endAt)}${endAt < text.length ? "…" : ""}`;
  const parts = [];
  const lower = text.toLowerCase();
  const wanted = term.toLowerCase();
  let start = 0;
  let index;
  while (wanted && (index = lower.indexOf(wanted, start)) !== -1) {
    parts.push(
      text.slice(start, index),
      <mark key={index}>{text.slice(index, index + wanted.length)}</mark>,
    );
    start = index + wanted.length;
  }
  parts.push(text.slice(start));
  return parts;
}

export default function MessageSearch({
  conversationId,
  name,
  onClose,
  onSelect,
  activeId,
}: {
  conversationId: string;
  name: string;
  onClose: () => void;
  onSelect: (message: ChatMessage) => void;
  activeId?: string;
}) {
  const [query, setQuery] = useState("");
  const term = query.trim();
  const [result, setResult] = useState<{
    term: string;
    messages: ChatMessage[];
    hasMore: boolean;
  }>({ term: "", messages: [], hasMore: false });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const version = useRef<object>({});

  useEffect(() => {
    const request = (version.current = {});
    if (!term) return;
    const timer = window.setTimeout(async () => {
      setLoading(true);
      setError("");
      try {
        const page = await searchMessages(conversationId, term);
        if (version.current === request) setResult({ term, ...page });
      } catch (failure) {
        if (version.current === request) setError((failure as Error).message);
      } finally {
        if (version.current === request) setLoading(false);
      }
    }, 250);
    return () => {
      window.clearTimeout(timer);
      version.current = {};
    };
  }, [conversationId, term, retry]);

  async function more() {
    if (loading) return;
    const request = (version.current = {});
    setLoading(true);
    setError("");
    try {
      const page = await searchMessages(
        conversationId,
        term,
        result.messages.at(-1)?.sequence,
      );
      if (version.current === request)
        setResult((previous) => ({
          term,
          messages: [...previous.messages, ...page.messages],
          hasMore: page.hasMore,
        }));
    } catch (failure) {
      if (version.current === request) setError((failure as Error).message);
    } finally {
      if (version.current === request) setLoading(false);
    }
  }

  const current = result.term === term;
  return (
    <section
      className="message-search"
      aria-label={`Search messages in ${name}`}
      onKeyDown={(event) => {
        if (event.key === "Escape") onClose();
      }}
    >
      <div className="message-search-heading">
        <strong>Search messages</strong>
        <small>{name}</small>
      </div>
      <div className="search-field">
        <label className="sr-only" htmlFor="message-search-input">
          Search messages in {name}
        </label>
        <input
          id="message-search-input"
          autoFocus
          type="search"
          placeholder="Search messages in this chat"
          maxLength={100}
          value={query}
          onChange={(event) => {
            if (event.target.value.trim() !== term) {
              version.current = {};
              setError("");
              setLoading(false);
            }
            setQuery(event.target.value);
          }}
        />
        <button
          className="icon-button"
          aria-label="Close message search"
          onClick={onClose}
        >
          ×
        </button>
      </div>
      <div
        className="message-search-results"
        aria-busy={!!term && (loading || (!current && !error))}
      >
        {!term ? (
          <p>Search this conversation’s message history.</p>
        ) : (
          <>
            {current && (
              <>
                <p role="status">
                  {result.messages.length
                    ? `${result.messages.length}${result.hasMore ? "+" : ""} matches`
                    : "No messages found"}
                </p>
                {result.messages.map((message) => (
                  <button
                    type="button"
                    className={`message-search-result ${activeId === message.id ? "active" : ""}`}
                    key={message.id}
                    aria-label={`Go to message from ${message.senderName}: ${message.text}`}
                    onClick={() => onSelect(message)}
                  >
                    <strong>{message.senderName}</strong>{" "}
                    <time dateTime={message.sentAt}>
                      {new Date(message.sentAt).toLocaleString()}
                    </time>
                    <p>{highlight(message.text, term)}</p>
                    {messageFiles(message).map((file) => (
                      <small key={file.id}>📎 {file.name}</small>
                    ))}
                  </button>
                ))}
              </>
            )}
            {error && (
              <p role="alert">
                {error}{" "}
                <button
                  onClick={() =>
                    current && result.hasMore
                      ? void more()
                      : setRetry((value) => value + 1)
                  }
                >
                  Retry search
                </button>
              </p>
            )}
            {(loading || (!current && !error)) && (
              <p role="status">Searching…</p>
            )}
            {current && result.hasMore && !error && (
              <button
                className="history-more"
                disabled={loading}
                onClick={() => void more()}
              >
                Load more matches
              </button>
            )}
          </>
        )}
      </div>
    </section>
  );
}
