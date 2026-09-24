import ConversationItem from "./ConversationItem";
import type { ItemProps } from "./ConversationItem";
import type { Conversation } from "../../types/chat";
// Render a named list of conversations or its empty state.
export default function ConversationSection({
  title,
  conversations,
  ...props
}: Omit<ItemProps, "conversation"> & {
  title: string;
  conversations: Conversation[];
}) {
  return (
    <section className="conversation-section" aria-label={title}>
      <h2>
        {title}
        <span>{conversations.length}</span>
      </h2>
      {conversations.length ? (
        <ul>
          {conversations.map((c) => (
            <ConversationItem key={c.id} conversation={c} {...props} />
          ))}
        </ul>
      ) : (
        <p className="list-empty">No {title.toLowerCase()} conversations</p>
      )}
    </section>
  );
}
