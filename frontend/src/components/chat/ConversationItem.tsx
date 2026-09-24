import Avatar from "./Avatar";
import ConversationActions from "./ConversationActions";
import { timeLabel } from "./chatState";
import type { Conversation, ConversationAction } from "../../types/chat";
export interface ItemProps {
  conversation: Conversation;
  selectedId: string | null;
  onSelect: (id: string) => void;
  onAction: (id: string, action: ConversationAction) => void;
}
// Render one conversation’s preview, status, and action menu.
export default function ConversationItem({
  conversation: c,
  selectedId,
  onSelect,
  onAction,
}: ItemProps) {
  const preview = c.lastMessage ?? c.messages.at(-1);
  return (
    <li
      className={`conversation-row ${selectedId === c.id ? "selected" : ""} ${c.type === "DIRECT" && c.unread > 0 ? "unread-direct" : ""}`}
    >
      <button
        className="conversation-select"
        aria-current={selectedId === c.id ? "true" : undefined}
        onClick={() => onSelect(c.id)}
      >
        <Avatar name={c.name} group={c.type === "GROUP"} />
        <span className="conversation-copy">
          <strong title={c.name}>
            {c.name}
            {c.type === "GROUP" && " (group)"}
          </strong>
          <span className="preview">
            {preview?.text ||
              preview?.attachments?.map((file) => file.name).join(", ") ||
              "Start a conversation"}
          </span>
          {(c.pinned || c.muted) && (
            <small>
              {[c.pinned && "Pinned", c.muted && "Muted"]
                .filter(Boolean)
                .join(" · ")}
            </small>
          )}
        </span>
        <span className="conversation-meta">
          <time
            dateTime={c.updatedAt}
            title={new Date(c.updatedAt).toLocaleString()}
          >
            {timeLabel(c.updatedAt)}
          </time>
          {c.unread > 0 && (
            <span className="unread" aria-label={`${c.unread} unread messages`}>
              {c.unread}
            </span>
          )}
        </span>
      </button>
      <ConversationActions conversation={c} onAction={onAction} />
    </li>
  );
}
