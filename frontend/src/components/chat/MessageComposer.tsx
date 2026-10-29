import { useRef, useState } from "react";
import {
  fileProblem,
  ALLOWED_FILE_EXTENSIONS,
} from "../../services/chatService";
import { fileSize } from "./chatState";

interface Props {
  name: string;
  draft: string;
  file: File | null;
  pending: boolean;
  error: string;
  disabled?: boolean;
  onDraftChange: (value: string) => void;
  onFileChange: (file: File | null) => void;
  onSend: (text: string) => void;
  onBlur?: () => void;
}
// Render the draft, attachment, and send controls for a conversation.
export default function MessageComposer({
  name,
  draft,
  file,
  pending,
  error,
  disabled,
  onDraftChange,
  onFileChange,
  onSend,
  onBlur,
}: Props) {
  const picker = useRef<HTMLInputElement>(null);
  const [fileError, setFileError] = useState("");
  const ready = (!!draft.trim() || !!file) && !pending && !disabled;
  // Submit a nonempty draft or a chosen file when sending is allowed.
  function send() {
    if (!ready) return;
    onSend(draft.trim());
  }
  // Accept a picked file only when the server would accept it too.
  function choose(picked: File | undefined) {
    if (picker.current) picker.current.value = "";
    if (!picked) return;
    const problem = fileProblem(picked);
    setFileError(problem);
    if (!problem) onFileChange(picked);
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
      {pending && <p role="status">{file ? "Uploading…" : "Sending…"}</p>}
      {file && (
        <ul className="pending-attachments">
          <li>
            <span title={file.name}>
              {file.name} · {fileSize(file.size)}
            </span>
            <button
              type="button"
              disabled={pending}
              aria-label={`Remove ${file.name}`}
              onClick={() => onFileChange(null)}
            >
              ×
            </button>
          </li>
        </ul>
      )}
      <div className="composer-input-row">
        <input
          ref={picker}
          id="chat-file"
          className="sr-only"
          type="file"
          tabIndex={-1}
          accept={ALLOWED_FILE_EXTENSIONS.map((e) => `.${e}`).join(",")}
          onChange={(event) => choose(event.target.files?.[0])}
        />
        <button
          className="attach-button"
          type="button"
          disabled={pending || disabled}
          aria-label="Attach a file"
          title="Attach a file (up to 50 MB)"
          onClick={() => picker.current?.click()}
        >
          ＋
        </button>
        <label className="sr-only" htmlFor="chat-draft">
          Message {name}
        </label>
        <textarea
          id="chat-draft"
          rows={1}
          placeholder={file ? "Add a caption (optional)" : `Message ${name}`}
          maxLength={4000}
          value={draft}
          disabled={pending || disabled}
          onChange={(event) => onDraftChange(event.target.value)}
          onBlur={onBlur}
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
        Enter to send · Shift+Enter for a new line · Files up to 50 MB
      </small>
    </form>
  );
}
