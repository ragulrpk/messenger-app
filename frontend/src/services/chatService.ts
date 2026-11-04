import type {
  ChatMessage,
  ConversationType,
  GroupMember,
  GroupRole,
  PresenceStatus,
  UserProfile,
} from "../types/chat";

const authUrl = import.meta.env?.VITE_AUTH_LOGIN_URL || "/api/v1/users/login";
const base =
  import.meta.env?.VITE_CHAT_API_URL ||
  authUrl.replace(/\/users\/login\/?$/, "/chat");
export interface ConversationSummary {
  historyFromSequence?: number;
  id: string;
  targetId: string | null;
  name: string;
  updatedAt: string;
  lastMessage: ChatMessage | null;
  // The caller's own saved preferences and unread count.
  pinned: boolean;
  muted: boolean;
  hidden: boolean;
  unread: number;
  // The other person's chosen status and note.
  targetStatus: PresenceStatus;
  targetNote: string;
  // How far the other person has received and read the conversation; in a
  // group, the lowest across the other members.
  targetDeliveredSequence: number;
  targetReadSequence: number;
  type: ConversationType;
  role: GroupRole;
  memberCount: number;
  // An announcement group, where only admins post.
  adminsOnly: boolean;
  // Archived by an administrator: readable, but nobody can post.
  archived?: boolean;
}
export interface ConversationPreferences {
  pinned?: boolean;
  muted?: boolean;
  hidden?: boolean;
  unread?: boolean;
}
export interface Presence {
  status: PresenceStatus;
  note: string;
}
export interface MessagePage {
  messages: ChatMessage[];
  hasMore: boolean;
  // The conversation's change counter, and what changed since the requested one.
  revision: number;
  hasMoreChanges: boolean;
  changed: ChatMessage[];
  removed: string[];
}
// The reactions the server accepts.
export const REACTIONS = ["👍", "❤️", "😂", "😮", "😢", "🙏"];
export interface ConversationPage {
  conversations: ConversationSummary[];
  nextCursor: string | null;
}

// Keep in step with app.files.max-size, app.files.video-max-size and
// app.files.allowed-extensions on the server.
export const MAX_FILE_BYTES = 5 * 1024 * 1024;
export const MAX_VIDEO_BYTES = 200 * 1024 * 1024;
export const VIDEO_EXTENSIONS = ["mp4", "mov", "webm"];
export const FILE_LIMITS_TEXT = "Videos up to 200 MB, other files up to 5 MB";
// Files sent together arrive as one message; keep in step with ChatService.MAX_FILES.
export const MAX_FILES = 5;
// One send is one upload request, held to spring.servlet.multipart.max-request-size
// and the proxy's client_max_body_size (201 MB), less room for the caption.
export const MAX_SEND_BYTES = 200 * 1024 * 1024;
export const ALLOWED_FILE_EXTENSIONS = [
  "pdf",
  "txt",
  "csv",
  "doc",
  "docx",
  "xls",
  "xlsx",
  "ppt",
  "pptx",
  "png",
  "jpg",
  "jpeg",
  "gif",
  "webp",
  "zip",
  "mp4",
  "mov",
  "webm",
  "mp3",
  "wav",
  "m4a",
  "ogg",
  "json",
  "xml",
  "yaml",
  "yml",
  "md",
  "log",
  "sql",
  "py",
  "js",
  "ts",
  "java",
  "html",
  "css",
  "rtf",
  "odt",
  "ods",
  "odp",
  "7z",
  "tar",
  "gz",
];

// Read a file's lower-case extension, or an empty string when it has none.
function extensionOf(file: File) {
  return file.name.includes(".")
    ? file.name.split(".").pop()!.toLowerCase()
    : "";
}
export const isVideo = (file: File) =>
  VIDEO_EXTENSIONS.includes(extensionOf(file));

// Explain why a file cannot be uploaded, or return an empty string when it can.
export function fileProblem(file: File) {
  if (!ALLOWED_FILE_EXTENSIONS.includes(extensionOf(file)))
    return `This file type is not allowed. Allowed: ${ALLOWED_FILE_EXTENSIONS.join(", ")}.`;
  if (file.size === 0) return "This file is empty.";
  if (isVideo(file)) {
    if (file.size > MAX_VIDEO_BYTES) return "Videos must be 200 MB or smaller.";
  } else if (file.size > MAX_FILE_BYTES)
    return "Files other than videos must be 5 MB or smaller.";
  return "";
}

// Call the chat API with session cookies, CSRF protection, and error handling.
export async function chatRequest<T>(
  url: string,
  body?: unknown,
  timeoutMs = 15000,
): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const multipart = body instanceof FormData;
  try {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (body !== undefined) {
      const csrfResponse = await fetch(
        authUrl.replace(/\/login\/?$/, "/csrf"),
        {
          credentials: "include",
          signal: controller.signal,
        },
      );
      if (!csrfResponse.ok)
        throw new Error(
          "Unable to initialize the session. Please sign in again.",
        );
      const csrf = await csrfResponse.json();
      if (
        csrf.headerName !== "X-CSRF-TOKEN" ||
        typeof csrf.token !== "string" ||
        !csrf.token
      )
        throw new Error("Invalid session response. Refresh and try again.");
      headers[csrf.headerName] = csrf.token;
      // The browser sets the multipart boundary itself.
      if (!multipart) headers["Content-Type"] = "application/json";
    }
    const response = await fetch(url, {
      method: body === undefined ? "GET" : "POST",
      headers,
      credentials: "include",
      signal: controller.signal,
      ...(body === undefined
        ? {}
        : { body: multipart ? body : JSON.stringify(body) }),
    });
    if (response.status === 401) {
      // Pass on why (signed in elsewhere, idle for five minutes…) for the sign-in page.
      const reason = await response.json().catch(() => null);
      window.dispatchEvent(
        new window.CustomEvent("messenger:session-expired", {
          detail:
            typeof reason?.message === "string"
              ? { code: reason.code, message: reason.message }
              : undefined,
        }),
      );
      throw new Error("Your session expired. Please sign in again.");
    }
    if (!response.ok) {
      if (response.status === 404)
        throw new Error("This person or conversation is no longer available.");
      if (response.status === 403)
        throw new Error("Request denied. Refresh and try again.");
      if (response.status === 409)
        throw new Error(
          "This retry conflicts with an earlier message. Refresh to check the conversation.",
        );
      if (response.status === 413)
        throw new Error(`File too large. ${FILE_LIMITS_TEXT}.`);
      if (response.status === 415)
        throw new Error("This file type is not allowed.");
      // Only file uploads and forwards are limited per day.
      if (response.status === 429)
        throw new Error("Daily upload limit reached. Try again tomorrow.");
      throw new Error("Unable to complete the request. Please retry.");
    }
    return await response.json();
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError")
      throw new Error(
        "The request timed out. Retry to check or send the message safely.",
      );
    if (error instanceof TypeError)
      throw new Error(
        "Unable to reach the server. Check your connection and retry.",
      );
    throw error;
  } finally {
    clearTimeout(timer);
  }
}
export interface ChatNotification {
  position: number;
  userId: string;
  conversationId: string;
  messageId: string;
  messageSequence: number;
  title: string;
  body: string;
  mentioned: boolean;
}
export const getNotifications = (after?: number) =>
  chatRequest<{
    notifications: ChatNotification[];
    cursor: number;
    hasMore: boolean;
    // Newer notifications wait for an older send to commit; ask again shortly.
    pending?: boolean;
  }>(`${base}/notifications${after === undefined ? "" : `?after=${after}`}`);
export const getPushConfig = () =>
  chatRequest<{ enabled: boolean; publicKey: string }>(`${base}/push/config`);
export const savePushSubscription = (subscription: PushSubscriptionJSON) =>
  chatRequest(`${base}/push/subscribe`, subscription);
export const getConversation = (id: string) =>
  chatRequest<ConversationSummary>(`${base}/conversations/${id}`);
// Search the user directory for matching people.
export const findPeople = (query: string) =>
  chatRequest<UserProfile[]>(`${base}/users?q=${encodeURIComponent(query)}`);
// Find many people at once by username; unknown names are left out of the result.
export const lookupPeople = (usernames: string[]) =>
  chatRequest<UserProfile[]>(`${base}/users/lookup`, { usernames });
// Tell senders their messages reached this device (the "delivered" tick).
export const markDelivered = () =>
  chatRequest<{ delivered: boolean }>(`${base}/delivered`, {});
// Fetch a page of conversation summaries.
export const listConversations = (cursor?: string) => {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
  return chatRequest<ConversationPage>(`${base}/conversations${query}`);
};
export const findGroups = (query: string) =>
  chatRequest<ConversationSummary[]>(
    `${base}/conversations/search?q=${encodeURIComponent(query)}`,
  );
// Open or create a direct conversation with a user.
export const openDirect = (userId: string) =>
  chatRequest<ConversationSummary>(`${base}/conversations/direct`, { userId });
// Create a group with the user as its admin.
export const createGroup = (
  name: string,
  memberIds: string[],
  adminsOnly: boolean,
) =>
  chatRequest<ConversationSummary>(`${base}/conversations/group`, {
    name,
    memberIds,
    adminsOnly,
  });
// Turn announcement mode (only admins post) on or off.
export const setAdminsOnly = (id: string, adminsOnly: boolean) =>
  chatRequest<ConversationSummary>(`${base}/conversations/${id}/settings`, {
    adminsOnly,
  });
// Group membership; changes are limited to admins, except leaving.
export const getMembers = (id: string) =>
  chatRequest<GroupMember[]>(`${base}/conversations/${id}/members`);
export const addMembers = (id: string, userIds: string[]) =>
  chatRequest<GroupMember[]>(`${base}/conversations/${id}/members`, {
    userIds,
  });
export const removeMember = (id: string, userId: string) =>
  chatRequest<GroupMember[]>(
    `${base}/conversations/${id}/members/${userId}/remove`,
    {},
  );
export const setMemberRole = (id: string, userId: string, role: GroupRole) =>
  chatRequest<GroupMember[]>(
    `${base}/conversations/${id}/members/${userId}/role`,
    { role },
  );
export const renameGroup = (id: string, name: string) =>
  chatRequest<ConversationSummary>(`${base}/conversations/${id}/rename`, {
    name,
  });
export const leaveGroup = (id: string) =>
  chatRequest<{ left: boolean }>(`${base}/conversations/${id}/leave`, {});
// Fetch messages before or after an optional sequence cursor, and optionally
// the messages changed since a revision.
export const getMessages = (
  id: string,
  cursor?: { before?: number; after?: number; changedAfter?: number },
) => {
  const params = new URLSearchParams();
  if (cursor?.before !== undefined) params.set("before", String(cursor.before));
  if (cursor?.after !== undefined) params.set("after", String(cursor.after));
  if (cursor?.changedAfter !== undefined)
    params.set("changedAfter", String(cursor.changedAfter));
  return chatRequest<MessagePage>(
    `${base}/conversations/${id}/messages?${params}`,
  );
};
export const clearChat = (id: string) =>
  chatRequest<ConversationSummary>(`${base}/conversations/${id}/clear`, {});
export const listSharedFiles = (id: string, before?: number) =>
  chatRequest<{ messages: ChatMessage[]; hasMore: boolean }>(
    `${base}/conversations/${id}/files${before === undefined ? "" : `?before=${before}`}`,
  );
export const searchMessages = (id: string, query: string, before?: number) => {
  const params = new URLSearchParams({ q: query });
  if (before !== undefined) params.set("before", String(before));
  return chatRequest<{ messages: ChatMessage[]; hasMore: boolean }>(
    `${base}/conversations/${id}/messages/search?${params}`,
  );
};
// Post a text message with its retry-safe client ID, optionally quoting another.
export const sendMessage = (
  id: string,
  clientId: string,
  text: string,
  replyToId?: string,
) =>
  chatRequest<ChatMessage>(`${base}/conversations/${id}/messages`, {
    clientId,
    text,
    replyToId,
  });
// Replace the text of one of the user's own messages.
export const editMessage = (messageId: string, text: string) =>
  chatRequest<ChatMessage>(`${base}/messages/${messageId}/edit`, { text });
// Delete a message for everyone (own messages) or only for the user.
export const deleteMessage = (messageId: string, everyone: boolean) =>
  chatRequest<{ deleted: boolean }>(`${base}/messages/${messageId}/delete`, {
    everyone,
  });
// Set the user's reaction to a message, or remove it with null.
export const reactToMessage = (messageId: string, emoji: string | null) =>
  chatRequest<ChatMessage>(`${base}/messages/${messageId}/reaction`, {
    emoji,
  });
// Copy a message, and any file, into another conversation.
export const forwardMessage = (
  messageId: string,
  conversationId: string,
  clientId: string,
) =>
  chatRequest<ChatMessage>(
    `${base}/messages/${messageId}/forward`,
    { conversationId, clientId },
    120000,
  );

// Upload files, in order, as one message with an optional caption, reusing the
// client ID on retry.
export const sendFiles = (
  id: string,
  clientId: string,
  files: File[],
  text: string,
  replyToId?: string,
) => {
  const form = new FormData();
  form.set("clientId", clientId);
  form.set("text", text);
  for (const file of files) form.append("file", file);
  if (replyToId) form.set("replyToId", replyToId);
  return chatRequest<ChatMessage>(
    `${base}/conversations/${id}/files`,
    form,
    // A 200 MB video needs far longer than a 5 MB file on a slow connection.
    files.some(isVideo) ? 900000 : 120000,
  );
};
// Build the member-only download address of a shared file.
export const fileUrl = (fileId: string) =>
  `${base}/files/${encodeURIComponent(fileId)}`;

// Save the caller's pin, mute, remove or manual-unread choice for a conversation.
export const updatePreferences = (
  id: string,
  preferences: ConversationPreferences,
) =>
  chatRequest<ConversationSummary>(
    `${base}/conversations/${id}/preferences`,
    preferences,
  );
// Mark messages read up to a sequence, or all of them when it is omitted.
export const markRead = (id: string, sequence?: number) =>
  chatRequest<ConversationSummary>(`${base}/conversations/${id}/read`, {
    sequence,
  });
// Load or save the caller's status and note, which their contacts see.
export const getPresence = () => chatRequest<Presence>(`${base}/presence`);
export const updatePresence = (presence: Presence) =>
  chatRequest<Presence>(`${base}/presence`, presence);

export const sendTyping = (id: string, active: boolean) =>
  chatRequest<{ accepted: boolean }>(`${base}/conversations/${id}/typing`, {
    active,
  });
