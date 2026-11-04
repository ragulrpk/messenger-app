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
      className={`conversation-row ${selectedId === c.id ? "selected" : ""} ${c.unread > 0 ? "has-unread" : ""}`}
    >
      <button
        className="conversation-select"
        aria-current={selectedId === c.id ? "true" : undefined}
        onClick={() => onSelect(c.id)}
      >
        <Avatar name={c.name} group={c.type === "GROUP"} status={c.status} />
        <span className="conversation-copy">
          <span className="conversation-title">
            <strong title={c.name}>{c.name}</strong>
            {c.type === "GROUP" && <span className="group-badge">Group</span>}
          </span>
          <span className="preview">
            {c.type === "GROUP" && preview && `${preview.senderName}: `}
            {(preview?.deleted && "This message was deleted") ||
              preview?.text ||
              (preview?.attachment && `📎 ${preview.attachment.name}`) ||
              "Start a conversation"}
          </span>
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
      {(c.pinned || c.muted) && (
        <span className="conversation-flags">
          {c.pinned && (
            <span
              className="conversation-flag"
              role="img"
              aria-label="Pinned"
              title="Pinned"
            >
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M9 3h6l-1 6 3 3v2H7v-2l3-3-1-6Z"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinejoin="round"
                />
                <path
                  d="M12 14v7"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                />
              </svg>
            </span>
          )}
          {c.muted && (
            <span
              className="conversation-flag"
              role="img"
              aria-label="Muted"
              title="Muted"
            >
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M3 9h4l5-4v14l-5-4H3Z"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinejoin="round"
                />
                <path
                  d="m16 9 5 6m0-6-5 6"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                />
              </svg>
            </span>
          )}
        </span>
      )}
      <ConversationActions conversation={c} onAction={onAction} />
    </li>
  );
}
