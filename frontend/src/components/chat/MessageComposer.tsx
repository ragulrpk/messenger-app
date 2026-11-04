import { useEffect, useRef, useState } from "react";
import {
  fileProblem,
  ALLOWED_FILE_EXTENSIONS,
  MAX_FILES,
  MAX_SEND_BYTES,
  FILE_LIMITS_TEXT,
} from "../../services/chatService";
import { fileSize, quoteText } from "./chatState";
import type { ChatMessage } from "../../types/chat";

// A message the composer is replying to or editing.
export interface ComposerContext {
  kind: "reply" | "edit";
  message: ChatMessage;
}
interface Props {
  name: string;
  draft: string;
  files: File[];
  pending: boolean;
  error: string;
  disabled?: boolean;
  context?: ComposerContext | null;
  onCancelContext?: () => void;
  onDraftChange: (value: string) => void;
  onFilesChange: (files: File[]) => void;
  onSend: (text: string) => void;
  onBlur?: () => void;
  // People who can be mentioned with "@" (other group members).
  mentionable?: { name: string; username: string }[];
}
// Render the draft, attachment, and send controls for a conversation.
export default function MessageComposer({
  name,
  draft,
  files,
  pending,
  error,
  disabled,
  context,
  onCancelContext,
  onDraftChange,
  onFilesChange,
  onSend,
  onBlur,
  mentionable = [],
}: Props) {
  const picker = useRef<HTMLInputElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const [fileError, setFileError] = useState("");
  // The "@word" being typed just before the cursor, and the highlighted suggestion.
  const [mention, setMention] = useState<{
    start: number;
    query: string;
  } | null>(null);
  const [activeSuggestion, setActiveSuggestion] = useState(0);
  const suggestions = mention
    ? mentionable
        .filter((p) =>
          `${p.name} ${p.username}`
            .toLocaleLowerCase()
            .includes(mention.query.toLocaleLowerCase()),
        )
        .slice(0, 8)
    : [];
  // Track an "@" word at the cursor so matching members can be suggested.
  function trackMention(value: string, caret: number) {
    const typed = /(^|\s)@([^\s@]*)$/u.exec(value.slice(0, caret));
    setMention(
      typed && mentionable.length
        ? { start: caret - typed[2].length - 1, query: typed[2] }
        : null,
    );
    setActiveSuggestion(0);
  }
  // Replace the "@word" with the chosen person's unique username, so the mention
  // always means that one person, even when two people share a name.
  function pickMention(person: { username: string }) {
    if (!mention) return;
    const end = mention.start + 1 + mention.query.length;
    const inserted = `@${person.username} `;
    onDraftChange(draft.slice(0, mention.start) + inserted + draft.slice(end));
    setMention(null);
    const caret = mention.start + inserted.length;
    window.requestAnimationFrame(() => {
      input.current?.focus();
      input.current?.setSelectionRange(caret, caret);
    });
  }
  const editing = context?.kind === "edit";
  // An edited file message may drop its caption; other edits need text.
  const ready =
    (editing
      ? !!draft.trim() || !!context.message.attachment
      : !!draft.trim() || files.length > 0) &&
    !pending &&
    !disabled;
  // Return to the text box once a send finishes (the box is disabled while it
  // runs, which drops focus) and when a reply or edit starts or ends.
  const settled = useRef({ pending, context });
  useEffect(() => {
    const before = settled.current;
    settled.current = { pending, context };
    if ((before.pending && !pending) || before.context !== context)
      input.current?.focus();
  }, [pending, context]);
  // Submit a nonempty draft or the chosen files when sending is allowed.
  function send() {
    if (!ready) return;
    onSend(draft.trim());
  }
  // Accept picked files only when the server would accept them too.
  function choose(list: FileList | null) {
    const picked = Array.from(list ?? []);
    if (picker.current) picker.current.value = "";
    if (!picked.length) return;
    const total = [...files, ...picked].reduce((sum, f) => sum + f.size, 0);
    const problem =
      files.length + picked.length > MAX_FILES
        ? `You can attach up to ${MAX_FILES} files at a time.`
        : (picked.map(fileProblem).find(Boolean) ??
          (total > MAX_SEND_BYTES
            ? "Files sent together must total 200 MB or less."
            : ""));
    setFileError(problem);
    if (!problem) onFilesChange([...files, ...picked]);
  }
  return (
    <form
      className="message-composer"
      onSubmit={(event) => {
        event.preventDefault();
        send();
      }}
    >
      {error && (
        <p className="attachment-error" role="alert">
          {error} Your draft is kept; use Retry send.
        </p>
      )}
      {fileError && (
        <p className="attachment-error" role="alert">
          {fileError}
        </p>
      )}
      {pending && (
        <p role="status">{files.length ? "Uploading…" : "Sending…"}</p>
      )}
      {context && (
        <div className="composer-context">
          <span>
            <strong>
              {editing
                ? "Editing message"
                : `Replying to ${context.message.senderName}`}
            </strong>
            {quoteText(context.message)}
          </span>
          <button
            type="button"
            aria-label={editing ? "Cancel editing" : "Cancel reply"}
            onClick={onCancelContext}
          >
            ×
          </button>
        </div>
      )}
      {!editing && files.length > 0 && (
        <ul className="pending-attachments">
          {files.map((file, index) => (
            <li key={index}>
              <span title={file.name}>
                {file.name} · {fileSize(file.size)}
              </span>
              <button
                type="button"
                disabled={pending}
                aria-label={`Remove ${file.name}`}
                onClick={() => onFilesChange(files.filter((f) => f !== file))}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
      {suggestions.length > 0 && (
        <ul
          id="mention-list"
          className="mention-suggestions"
          role="listbox"
          aria-label="Mention someone"
        >
          {suggestions.map((person, index) => (
            <li
              key={person.username}
              role="option"
              aria-selected={index === activeSuggestion}
              className={index === activeSuggestion ? "active" : ""}
              onMouseDown={(event) => {
                // Keep focus in the text box while picking.
                event.preventDefault();
                pickMention(person);
              }}
            >
              <strong>{person.name}</strong>
              <small>@{person.username}</small>
            </li>
          ))}
        </ul>
      )}
      <div className="composer-input-row">
        <input
          ref={picker}
          id="chat-file"
          className="sr-only"
          type="file"
          multiple
          tabIndex={-1}
          accept={ALLOWED_FILE_EXTENSIONS.map((e) => `.${e}`).join(",")}
          onChange={(event) => choose(event.target.files)}
        />
        <button
          className="attach-button"
          type="button"
          disabled={pending || disabled || editing}
          aria-label="Attach a file"
          title={`Attach up to ${MAX_FILES} files (${FILE_LIMITS_TEXT.toLowerCase()})`}
          onClick={() => picker.current?.click()}
        >
          ＋
        </button>
        <label className="sr-only" htmlFor="chat-draft">
          Message {name}
        </label>
        <textarea
          ref={input}
          id="chat-draft"
          rows={1}
          placeholder={
            files.length ? "Add a caption (optional)" : `Message ${name}`
          }
          maxLength={4000}
          value={draft}
          disabled={pending || disabled}
          onChange={(event) => {
            onDraftChange(event.target.value);
            trackMention(
              event.target.value,
              event.target.selectionStart ?? event.target.value.length,
            );
          }}
          onBlur={onBlur}
          aria-autocomplete={mentionable.length ? "list" : undefined}
          aria-controls={suggestions.length ? "mention-list" : undefined}
          onKeyDown={(event) => {
            if (suggestions.length) {
              if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                event.preventDefault();
                const step = event.key === "ArrowDown" ? 1 : -1;
                setActiveSuggestion(
                  (i) => (i + step + suggestions.length) % suggestions.length,
                );
                return;
              }
              if (event.key === "Enter" || event.key === "Tab") {
                event.preventDefault();
                pickMention(suggestions[activeSuggestion] ?? suggestions[0]);
                return;
              }
              if (event.key === "Escape") {
                event.preventDefault();
                setMention(null);
                return;
              }
            }
            if (event.key === "Escape" && context) {
              event.preventDefault();
              onCancelContext?.();
              return;
            }
            if (
              event.key === "Enter" &&
              !event.shiftKey &&
              !event.nativeEvent.isComposing
            ) {
              event.preventDefault();
              if (!event.repeat) send();
            }
          }}
        />
        <button
          className="send-button"
          disabled={!ready}
          type="submit"
          aria-label={error ? "Retry send" : "Send message"}
          title={error ? "Retry send" : "Send message"}
        >
          <svg
            width="19"
            height="19"
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="m21 3-7 18-4-7-7-4 18-7Z"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinejoin="round"
            />
            <path
              d="m10 14 6-6"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
            />
          </svg>
        </button>
      </div>
      <small className="composer-help">
        Enter to send · Shift+Enter for a new line · Up to {MAX_FILES} files ·{" "}
        {FILE_LIMITS_TEXT}
      </small>
    </form>
  );
}
