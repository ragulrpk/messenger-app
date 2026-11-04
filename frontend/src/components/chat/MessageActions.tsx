import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { REACTIONS } from "../../services/chatService";
import type { ChatMessage } from "../../types/chat";

export interface MessageHandlers {
  // Missing where the user cannot post, such as an announcement group.
  onReply?: (message: ChatMessage) => void;
  onEdit: (message: ChatMessage) => void;
  onReact: (message: ChatMessage, emoji: string | null) => void;
  onForward: (message: ChatMessage, conversationId: string) => void;
  onDelete: (message: ChatMessage, everyone: boolean) => void;
}
// Render the reply, react, edit, forward, and delete menu for one message.
export default function MessageActions({
  message,
  own,
  forwardTargets,
  handlers,
}: {
  message: ChatMessage;
  own: boolean;
  forwardTargets: { id: string; name: string }[];
  handlers: MessageHandlers;
}) {
  const [open, setOpen] = useState(false);
  const [forwarding, setForwarding] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const popover = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ top: 0, left: 0 });
  const mine = message.reactions?.find((r) => r.mine)?.emoji ?? null;
  const live = !message.deleted;
  useLayoutEffect(() => {
    if (!open) return;
    const pane = trigger.current?.closest(".chat-messages");
    // Escape scroll clipping while keeping the menu inside this conversation.
    function place() {
      if (!trigger.current || !popover.current) return;
      const anchor = trigger.current.getBoundingClientRect();
      const gap = 8;
      const bounds = pane?.getBoundingClientRect();
      const left = Math.max(0, bounds?.left ?? 0) + gap;
      const right =
        Math.min(window.innerWidth, bounds?.right ?? window.innerWidth) - gap;
      const upper = Math.max(0, bounds?.top ?? 0) + gap;
      const bottom =
        Math.min(window.innerHeight, bounds?.bottom ?? window.innerHeight) -
        gap;
      popover.current.style.maxWidth = `${Math.max(0, right - left)}px`;
      popover.current.style.maxHeight = `${Math.max(0, bottom - upper)}px`;
      const menu = popover.current.getBoundingClientRect();
      const below = bottom - anchor.bottom - gap;
      const top =
        below >= menu.height || below >= anchor.top - upper - gap
          ? anchor.bottom + gap
          : anchor.top - menu.height - gap;
      setPosition({
        top: Math.max(upper, Math.min(top, bottom - menu.height)),
        left: Math.max(
          left,
          Math.min(
            own ? anchor.left : anchor.right - menu.width,
            right - menu.width,
          ),
        ),
      });
    }
    place();
    const observer =
      typeof ResizeObserver === "undefined" ? null : new ResizeObserver(place);
    if (pane) observer?.observe(pane);
    observer?.observe(popover.current!);
    window.addEventListener("resize", place);
    window.addEventListener("scroll", place, true);
    return () => {
      observer?.disconnect();
      window.removeEventListener("resize", place);
      window.removeEventListener("scroll", place, true);
    };
  }, [open, forwarding, own, forwardTargets.length]);
  useEffect(() => {
    if (!open) return;
    // Dismiss the menu when a pointer press occurs outside it.
    function close(e: PointerEvent) {
      if (
        !root.current?.contains(e.target as Node) &&
        !popover.current?.contains(e.target as Node)
      )
        setOpen(false);
    }
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, [open]);
  // Close the menu, return focus to its button, and run the chosen action.
  function run(action: () => void) {
    setOpen(false);
    setForwarding(false);
    trigger.current?.focus();
    action();
  }
  return (
    <div
      className="message-actions"
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
        className="icon-button message-actions-trigger"
        aria-label="Message actions"
        aria-expanded={open}
        onClick={() => {
          setOpen(!open);
          setForwarding(false);
        }}
      >
        ⋯
      </button>
      {open &&
        createPortal(
          <div
            ref={popover}
            className="action-popover message-popover"
            style={{
              position: "fixed",
              top: position.top,
              left: position.left,
              right: "auto",
            }}
            role="group"
            aria-label="Message actions"
          >
            {live && (
              <div className="reaction-picker">
                {REACTIONS.map((emoji) => (
                  <button
                    key={emoji}
                    aria-label={`React with ${emoji}`}
                    aria-pressed={mine === emoji}
                    onClick={() =>
                      run(() =>
                        handlers.onReact(
                          message,
                          mine === emoji ? null : emoji,
                        ),
                      )
                    }
                  >
                    {emoji}
                  </button>
                ))}
              </div>
            )}
            {live && handlers.onReply && (
              <button onClick={() => run(() => handlers.onReply?.(message))}>
                Reply
              </button>
            )}
            {live && own && (
              <button onClick={() => run(() => handlers.onEdit(message))}>
                Edit
              </button>
            )}
            {live && forwardTargets.length > 0 && (
              <button
                aria-expanded={forwarding}
                onClick={() => setForwarding(!forwarding)}
              >
                Forward…
              </button>
            )}
            {forwarding &&
              forwardTargets.map((target) => (
                <button
                  key={target.id}
                  className="forward-target"
                  onClick={() =>
                    run(() => handlers.onForward(message, target.id))
                  }
                >
                  Forward to {target.name}
                </button>
              ))}
            <button
              onClick={() => {
                if (window.confirm("Delete this message for you?"))
                  run(() => handlers.onDelete(message, false));
              }}
            >
              Delete for me
            </button>
            {live && own && (
              <button
                onClick={() => {
                  if (window.confirm("Delete this message for everyone?"))
                    run(() => handlers.onDelete(message, true));
                }}
              >
                Delete for everyone
              </button>
            )}
          </div>,
          document.body,
        )}
    </div>
  );
}
