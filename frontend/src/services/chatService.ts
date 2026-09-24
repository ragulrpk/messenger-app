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

// Call the chat API with session cookies, CSRF protection, and error handling.
export async function chatRequest<T>(url: string, body?: unknown): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
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
      headers["Content-Type"] = "application/json";
    }
    const response = await fetch(url, {
      method: body === undefined ? "GET" : "POST",
      headers,
      credentials: "include",
      signal: controller.signal,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
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
