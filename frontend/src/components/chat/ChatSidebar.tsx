import CurrentUserHeader from "./CurrentUserHeader";
import ConversationSearch from "./ConversationSearch";
import ConversationSection from "./ConversationSection";
import { sections } from "./chatState";
import type {
  Conversation,
  ConversationAction,
  ConversationType,
  User,
} from "../../types/chat";
// Render directory search and the conversation list.
export default function ChatSidebar(props: {
  user: User;
  conversations: Conversation[];
  selectedId: string | null;
  query: string;
  onQuery: (value: string) => void;
  onSelect: (id: string) => void;
  onResult: (type: ConversationType, id: string, name: string) => void;
  onAction: (id: string, action: ConversationAction) => void;
  loading?: boolean;
  error?: string;
  onRetry?: () => void;
  onMore?: () => void;
  opening?: boolean;
  openError?: string;
}) {
  const { pinned, recent } = sections(props.conversations);
  const shared = {
    selectedId: props.selectedId,
    onSelect: props.onSelect,
    onAction: props.onAction,
  };
  return (
    <aside className="conversation-sidebar">
      <CurrentUserHeader user={props.user} />
      <ConversationSearch
        query={props.query}
        onQuery={props.onQuery}
        onSelect={props.onResult}
      />
      {props.loading && (
        <p className="messaging-notice" role="status">
          Loading conversations…
        </p>
      )}
      {props.error && (
        <p className="messaging-notice" role="alert">
          {props.error}{" "}
          <button onClick={props.onRetry}>Retry conversations</button>
        </p>
      )}
      {props.opening && (
        <p className="messaging-notice" role="status">
          Opening conversation…
        </p>
      )}
      {props.openError && (
        <p className="messaging-notice" role="alert">
          {props.openError} Select the person to retry.
        </p>
      )}
      <ConversationSection title="PINNED" conversations={pinned} {...shared} />
      <ConversationSection title="RECENT" conversations={recent} {...shared} />
      {props.onMore && (
        <button className="history-more" onClick={props.onMore}>
          Load more conversations
        </button>
      )}
    </aside>
  );
}
