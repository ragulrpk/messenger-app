export type ConversationType = "DIRECT" | "GROUP";
export type PresenceStatus = "online" | "offline" | "meeting" | "busy";
export interface UserProfile {
  administrator?: boolean;
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
  attachments?: File[];
}
export interface Conversation {
  id: string;
  type: ConversationType;
  targetId: string;
  name: string;
  pinned: boolean;
  unread: number;
  muted: boolean;
  hidden: boolean;
  updatedAt: string;
  messages: ChatMessage[];
  lastMessage?: ChatMessage | null;
}
export type ConversationAction = "pin" | "read" | "mute" | "remove";
