import type {
  ChatAttachment,
  ChatMention,
  ChatMessage,
  ChatReply,
  Conversation,
  ConversationAction,
  Group,
  User,
} from "../../types/chat";
// "@" + a username (which may itself be an email address), not preceded by part of
// a word or address. Kept in step with the server's mention pattern.
const MENTION =
  /(?<![\p{L}\p{N}._%+@-])@([\p{L}\p{N}._%+-]+(?:@[\p{L}\p{N}.-]+)?)/gu;
// Split text into plain parts and mentions of the given people. Mentions are
// written as "@username", which is unique, and shown as "@Name". A trailing full
// stop or dash ("@bob.") is read as ending the sentence, not the username.
export function splitMentions(text: string, people: ChatMention[]) {
  const byUsername = new Map(
    people.map((p) => [p.username.toLowerCase(), p] as const),
  );
  const parts: { text: string; mention: ChatMention | null }[] = [];
  if (!byUsername.size || !text.includes("@")) return [{ text, mention: null }];
  let last = 0;
  for (const match of text.matchAll(MENTION)) {
    const token = match[1];
    let person = byUsername.get(token.toLowerCase());
    let length = token.length;
    if (!person) {
      const trimmed = token.replace(/[.-]+$/, "");
      person = byUsername.get(trimmed.toLowerCase());
      length = trimmed.length;
    }
    if (!person) continue;
    if (match.index > last)
      parts.push({ text: text.slice(last, match.index), mention: null });
    parts.push({ text: `@${person.name}`, mention: person });
    last = match.index + 1 + length;
  }
  if (last < text.length) parts.push({ text: text.slice(last), mention: null });
  return parts;
}
// Whether a message mentions the person with this username.
export function mentions(text: string, username: string | undefined) {
  return (
    !!username &&
    splitMentions(text, [{ id: "", username, name: "" }]).some((p) => p.mention)
  );
}
// Give each group member a steady name colour, picked from their ID.
const senderColors = [
  "#a35335",
  "#176b5b",
  "#5b4bb7",
  "#b2386b",
  "#2f6fb0",
  "#8a6d12",
  "#3c7d3a",
  "#9c4fb0",
];
export function senderColor(id: string) {
  let hash = 0;
  for (const char of id) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  return senderColors[Math.abs(hash) % senderColors.length];
}
// Every file a message carries, in order; older messages have only attachment.
export function messageFiles(message: ChatMessage): ChatAttachment[] {
  if (message.attachments?.length) return message.attachments;
  return message.attachment ? [message.attachment] : [];
}
// Summarize a quoted message in one line.
export function quoteText(reply: ChatReply | ChatMessage) {
  if (reply.deleted) return "Deleted message";
  const file =
    "attachmentName" in reply ? reply.attachmentName : reply.attachment?.name;
  return reply.text || (file ? `📎 ${file}` : "");
}
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
// Format a byte count for display next to a file name.
export function fileSize(bytes: number) {
  return bytes < 1024 * 1024
    ? `${Math.max(1, Math.ceil(bytes / 1024))} KB`
    : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
