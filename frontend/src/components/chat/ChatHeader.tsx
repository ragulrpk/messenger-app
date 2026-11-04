import Avatar from "./Avatar";
import ConversationActions from "./ConversationActions";
import { statusLabels } from "./presence";
import type { Conversation, ConversationAction } from "../../types/chat";
// Show the selected conversation’s title and controls.
export default function ChatHeader({
  conversation: c,
  onBack,
  onAction,
  onGroupInfo,
  onSearch,
  searching,
  onSharedFiles,
  onClearChat,
  clearing,
}: {
  conversation: Conversation;
  onBack: () => void;
  onAction: (id: string, action: ConversationAction) => void;
  onGroupInfo?: () => void;
  onSearch: () => void;
  searching: boolean;
  onSharedFiles?: () => void;
  onClearChat?: () => void;
  clearing?: boolean;
}) {
  const group = c.type === "GROUP";
  // A group shows its size; a direct chat shows the other person's status and note.
  const details = group
    ? [
        c.adminsOnly && "Announcements",
        c.memberCount && `${c.memberCount} members`,
        c.muted && "Muted",
      ]
    : [c.status && statusLabels[c.status], c.note, c.muted && "Muted"];
  return (
    <header className="conversation-header">
      <button
        className="mobile-back icon-button"
        onClick={onBack}
        aria-label="Back to conversations"
      >
        ←
      </button>
      <Avatar name={c.name} group={group} status={c.status} />
      <div className="header-name">
        <div className="header-title">
          <h1 title={c.name}>
            {group && onGroupInfo ? (
              <button
                className="group-title-button"
                onClick={onGroupInfo}
                aria-label={`View details for ${c.name}`}
              >
                {c.name}
              </button>
            ) : (
              c.name
            )}
          </h1>
          {group && <span className="group-badge">Group</span>}
        </div>
        {details.some(Boolean) && (
          <small title={c.note || undefined}>
            {details.filter(Boolean).join(" · ")}
          </small>
        )}
      </div>
      {group && onGroupInfo && (
        <button
          className="icon-button"
          aria-label="Group info"
          title="Group info"
          onClick={onGroupInfo}
        >
          ⓘ
        </button>
      )}
      <button
        className="icon-button"
        aria-label="Search messages"
        title="Search messages"
        aria-expanded={searching}
        onClick={onSearch}
      >
        ⌕
      </button>
      <ConversationActions
        conversation={c}
        onAction={onAction}
        onSharedFiles={onSharedFiles}
        onClearChat={onClearChat}
        clearing={clearing}
      />
    </header>
  );
}
