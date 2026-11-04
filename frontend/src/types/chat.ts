export type ConversationType = "DIRECT" | "GROUP";
export type PresenceStatus = "online" | "offline" | "meeting" | "busy";
export interface UserProfile {
  administrator?: boolean;
  // The password is temporary and must be replaced before anything else works.
  mustChangePassword?: boolean;
  // When an administrator last reset the password (ISO time), if ever.
  passwordResetAt?: string;
  // The employee's GPF number (their login ID), if they have one.
  gpfNumber?: string;
  cadre?: string;
  id: string;
  name: string;
  username?: string;
  phoneNumber?: string;
  email?: string;
  dateOfBirth?: string;
  designation?: string;
  circle?: string;
  zone?: string;
  division?: string;
  dateOfJoining?: string;
}
export interface User extends UserProfile {
  online: boolean;
  avatarUrl?: string;
  status?: PresenceStatus;
  note?: string;
}
export interface Group {
  id: string;
  name: string;
  memberIds: string[];
}
export interface ChatMessage {
  id: string;
  sequence?: number;
  clientId?: string;
  senderId: string;
  senderName: string;
  text: string;
  sentAt: string;
  // The first file; attachments lists every file sent with the message, in order.
  attachment?: ChatAttachment | null;
  attachments?: ChatAttachment[];
  edited?: boolean;
  // A message deleted for everyone keeps its place but has no text or file.
  deleted?: boolean;
  forwarded?: boolean;
  replyTo?: ChatReply | null;
  reactions?: ChatReaction[];
  // People mentioned as "@username", resolved by the server.
  mentions?: ChatMention[];
}
export interface ChatMention {
  id: string;
  username: string;
  name: string;
}
// The quoted message shown above a reply.
export interface ChatReply {
  id: string;
  senderName: string;
  text: string;
  attachmentName: string | null;
  deleted: boolean;
}
export interface ChatReaction {
  emoji: string;
  count: number;
  mine: boolean;
}
export interface ChatAttachment {
  id: string;
  name: string;
  contentType: string;
  size: number;
  available: boolean;
}
export interface Conversation {
  historyFromSequence?: number;
  id: string;
  type: ConversationType;
  targetId: string | null;
  name: string;
  pinned: boolean;
  unread: number;
  muted: boolean;
  hidden: boolean;
  updatedAt: string;
  messages: ChatMessage[];
  lastMessage?: ChatMessage | null;
  // The other person's chosen status and note.
  status?: PresenceStatus;
  note?: string;
  // The other person's delivered and read positions, for receipts on sent messages.
  deliveredSequence?: number;
  readSequence?: number;
  // In a group: the user's own role and how many people belong to it.
  role?: GroupRole;
  memberCount?: number;
  // An announcement group, where only admins post.
  adminsOnly?: boolean;
  // Archived by an administrator: history stays readable, nobody can post.
  archived?: boolean;
}
export type ConversationAction = "pin" | "read" | "mute" | "remove";
export type GroupRole = "admin" | "member";
export interface GroupMember {
  id: string;
  username: string;
  name: string;
  role: GroupRole;
}
