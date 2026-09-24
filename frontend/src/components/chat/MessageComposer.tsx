interface Props {
  name: string;
  draft: string;
  pending: boolean;
  error: string;
  disabled?: boolean;
  onDraftChange: (value: string) => void;
  onSend: (text: string) => void;
}
// Render the text draft and send controls for a conversation.
export default function MessageComposer({
  name,
  draft,
  pending,
  error,
  disabled,
  onDraftChange,
  onSend,
}: Props) {
  // Submit a nonempty draft when sending is allowed.
  function send() {
    if (!draft.trim() || pending || disabled) return;
    onSend(draft.trim());
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
      {pending && <p role="status">Sending…</p>}
      <div className="composer-input-row">
        <button
          className="attach-button"
          type="button"
          disabled
          aria-label="Attachments coming soon"
          title="File uploads are not available yet"
        >
          ＋
        </button>
        <label className="sr-only" htmlFor="chat-draft">
          Message {name}
        </label>
        <textarea
          id="chat-draft"
          rows={1}
          placeholder={`Message ${name}`}
          maxLength={4000}
          value={draft}
          disabled={pending || disabled}
          onChange={(event) => onDraftChange(event.target.value)}
          onKeyDown={(event) => {
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
          disabled={!draft.trim() || pending || disabled}
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
        Enter to send · Shift+Enter for a new line · Text messages only
      </small>
    </form>
  );
}
