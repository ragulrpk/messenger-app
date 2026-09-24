import Avatar from "./Avatar";
import ConversationActions from "./ConversationActions";
import type { Conversation, ConversationAction } from "../../types/chat";
// Show the selected conversation’s title and controls.
export default function ChatHeader({
  conversation: c,
  onBack,
  onAction,
}: {
  conversation: Conversation;
  onBack: () => void;
  onAction: (id: string, action: ConversationAction) => void;
}) {
  return (
    <header className="conversation-header">
      <button
        className="mobile-back icon-button"
        onClick={onBack}
        aria-label="Back to conversations"
      >
        ←
      </button>
      <Avatar name={c.name} group={c.type === "GROUP"} />
      <div className="header-name">
        <h1 title={c.name}>
          {c.name}
          {c.type === "GROUP" && " (group)"}
        </h1>
        {c.muted && <small>Muted</small>}
      </div>
      <ConversationActions conversation={c} onAction={onAction} />
    </header>
  );
}
