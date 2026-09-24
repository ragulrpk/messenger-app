import { useEffect, useRef, useState } from "react";
import type { Conversation, ConversationAction } from "../../types/chat";
// Render the menu of actions for one conversation.
export default function ConversationActions({
  conversation: c,
  onAction,
}: {
  conversation: Conversation;
  onAction: (id: string, action: ConversationAction) => void;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    // Dismiss the action menu when a pointer press occurs outside it.
    function close(e: PointerEvent) {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, [open]);
  // Apply a selected action and close the menu.
  function act(action: ConversationAction) {
    setOpen(false);
    trigger.current?.focus();
    if (
      action === "remove" &&
      !window.confirm(`Remove ${c.name} from recent conversations?`)
    )
      return;
    onAction(c.id, action);
  }
  return (
    <div
      className="conversation-actions"
      ref={root}
      onKeyDown={(e) => {
        if (e.key === "Escape") {
          setOpen(false);
          trigger.current?.focus();
        }
      }}
    >
      <button
        ref={trigger}
        className="icon-button"
        aria-label={`Actions for ${c.name}`}
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        ⋯
      </button>
      {open && (
        <div
          className="action-popover"
          role="group"
          aria-label={`Actions for ${c.name}`}
        >
          <strong>{c.name}</strong>
          <button onClick={() => act("pin")}>
            {c.pinned ? "Unpin" : "Pin"} {c.name}
          </button>
          <button onClick={() => act("read")}>
            Mark {c.name} as {c.unread ? "read" : "unread"}
          </button>
          <button onClick={() => act("mute")}>
            {c.muted ? "Unmute" : "Mute"} {c.name}
          </button>
          <button onClick={() => act("remove")}>
            Remove {c.name} from recent list
          </button>
        </div>
      )}
    </div>
  );
}
