import type {
  Conversation,
  ConversationAction,
  Group,
  User,
} from "../../types/chat";
// Find matching people and groups for a trimmed search term.
export function searchDirectory(
  query: string,
  people: User[],
  groups: Group[],
) {
  const term = query.trim().toLocaleLowerCase();
  return {
    people: term
      ? people.filter((p) => p.name.toLocaleLowerCase().includes(term))
      : [],
    groups: term
      ? groups.filter((g) => g.name.toLocaleLowerCase().includes(term))
      : [],
  };
}
// Deduplicate visible conversations and split pinned from recent items.
export function sections(conversations: Conversation[]) {
  const visible = [
    ...new Map(
      conversations.filter((c) => !c.hidden).map((c) => [c.id, c]),
    ).values(),
  ];
  return {
    pinned: visible.filter((c) => c.pinned),
    recent: visible
      .filter((c) => !c.pinned)
      .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt)),
  };
}
// Update one conversation for a pin, read, mute, or remove action.
export function applyAction(
  conversations: Conversation[],
  id: string,
  action: ConversationAction,
): Conversation[] {
  return conversations.map((c) =>
    c.id !== id
      ? c
      : action === "pin"
        ? { ...c, pinned: !c.pinned }
        : action === "read"
          ? { ...c, unread: c.unread ? 0 : 1 }
          : action === "mute"
            ? { ...c, muted: !c.muted }
            : { ...c, hidden: true, pinned: false },
  );
}
// Format a message or conversation time for display.
export function timeLabel(date: string) {
  return new Date(date).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });
}
