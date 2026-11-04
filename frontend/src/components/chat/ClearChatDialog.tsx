import { useEffect, useRef } from "react";

export default function ClearChatDialog({
  name,
  pending,
  onCancel,
  onConfirm,
}: {
  name: string;
  pending: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const popup = dialog.current!;
    const previous = document.activeElement as HTMLElement | null;
    if (typeof popup.showModal === "function") popup.showModal();
    else popup.setAttribute("open", "");
    cancel.current?.focus();
    return () => {
      if (typeof popup.close === "function") popup.close();
      else popup.removeAttribute("open");
      if (previous?.isConnected) previous.focus();
    };
  }, []);
  return (
    <dialog
      ref={dialog}
      className="clear-chat-dialog"
      aria-labelledby="clear-chat-title"
      aria-describedby="clear-chat-description"
      onCancel={(event) => {
        event.preventDefault();
        if (!pending) onCancel();
      }}
    >
      <h2 id="clear-chat-title">Clear chat?</h2>
      <p id="clear-chat-description">
        Are you sure you want to clear all messages in <strong>{name}</strong>{" "}
        for you? This cannot be undone. Other members will keep their messages.
      </p>
      <div className="clear-chat-dialog-actions">
        <button ref={cancel} disabled={pending} onClick={onCancel}>
          Cancel
        </button>
        <button
          className="clear-chat-confirm"
          disabled={pending}
          onClick={onConfirm}
        >
          {pending ? "Clearing…" : "Clear chat"}
        </button>
      </div>
    </dialog>
  );
}
