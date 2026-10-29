import type { ChatMessage, UserProfile } from "../types/chat";

const authUrl = import.meta.env?.VITE_AUTH_LOGIN_URL || "/api/v1/users/login";
const base =
  import.meta.env?.VITE_CHAT_API_URL ||
  authUrl.replace(/\/users\/login\/?$/, "/chat");
export interface ConversationSummary {
  id: string;
  targetId: string;
  name: string;
  updatedAt: string;
  lastMessage: ChatMessage | null;
}
export interface MessagePage {
  messages: ChatMessage[];
  hasMore: boolean;
}
export interface ConversationPage {
  conversations: ConversationSummary[];
  nextCursor: string | null;
}

// Keep in step with app.files.max-size and app.files.allowed-extensions on the server.
export const MAX_FILE_BYTES = 50 * 1024 * 1024;
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
];

// Explain why a file cannot be uploaded, or return an empty string when it can.
export function fileProblem(file: File) {
  const extension = file.name.includes(".")
    ? file.name.split(".").pop()!.toLowerCase()
    : "";
  if (!ALLOWED_FILE_EXTENSIONS.includes(extension))
    return `This file type is not allowed. Allowed: ${ALLOWED_FILE_EXTENSIONS.join(", ")}.`;
  if (file.size === 0) return "This file is empty.";
  if (file.size > MAX_FILE_BYTES) return "Files must be 50 MB or smaller.";
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
      window.dispatchEvent(new window.Event("messenger:session-expired"));
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
        throw new Error("Files must be 50 MB or smaller.");
      if (response.status === 415)
        throw new Error("This file type is not allowed.");
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
// Search the user directory for matching people.
export const findPeople = (query: string) =>
  chatRequest<UserProfile[]>(`${base}/users?q=${encodeURIComponent(query)}`);
// Fetch a page of conversation summaries.
export const listConversations = (cursor?: string) => {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
  return chatRequest<ConversationPage>(`${base}/conversations${query}`);
};
// Open or create a direct conversation with a user.
export const openDirect = (userId: string) =>
  chatRequest<ConversationSummary>(`${base}/conversations/direct`, { userId });
// Fetch messages before or after an optional sequence cursor.
export const getMessages = (
  id: string,
  cursor?: { before?: number; after?: number },
) => {
  const params = new URLSearchParams();
  if (cursor?.before !== undefined) params.set("before", String(cursor.before));
  if (cursor?.after !== undefined) params.set("after", String(cursor.after));
  return chatRequest<MessagePage>(
    `${base}/conversations/${id}/messages?${params}`,
  );
};
// Post a text message with its retry-safe client ID.
export const sendMessage = (id: string, clientId: string, text: string) =>
  chatRequest<ChatMessage>(`${base}/conversations/${id}/messages`, {
    clientId,
    text,
  });

// Upload one file with an optional caption, reusing the client ID on retry.
export const sendFile = (
  id: string,
  clientId: string,
  file: File,
  text: string,
) => {
  const form = new FormData();
  form.set("clientId", clientId);
  form.set("text", text);
  form.set("file", file);
  return chatRequest<ChatMessage>(
    `${base}/conversations/${id}/files`,
    form,
    120000,
  );
};
// Build the member-only download address of a shared file.
export const fileUrl = (fileId: string) =>
  `${base}/files/${encodeURIComponent(fileId)}`;

export const sendTyping = (id: string, active: boolean) =>
  chatRequest<{ accepted: boolean }>(`${base}/conversations/${id}/typing`, {
    active,
  });
