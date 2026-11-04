import { useEffect, useRef, useState } from "react";
import Brand from "../../components/Brand";
import ChatSettings from "../../components/chat/ChatSettings";
import ChatSidebar from "../../components/chat/ChatSidebar";
import SharedFiles from "../../components/chat/SharedFiles";
import MessageSearch from "../../components/chat/MessageSearch";
import ClearChatDialog from "../../components/chat/ClearChatDialog";
import ChatHeader from "../../components/chat/ChatHeader";
import MessageList from "../../components/chat/MessageList";
import MessageComposer from "../../components/chat/MessageComposer";
import GroupInfo from "../../components/chat/GroupInfo";
import NewGroup from "../../components/chat/NewGroup";
import type { ComposerContext } from "../../components/chat/MessageComposer";
import type { MessageHandlers } from "../../components/chat/MessageActions";
import EmptyChatState from "../../components/chat/EmptyChatState";
import {
  getMembers,
  getConversation,
  getPresence,
  updatePreferences,
  updatePresence,
} from "../../services/chatService";
import useMessaging from "./useMessaging";
import type {
  ChatMessage,
  Conversation,
  ConversationAction,
  GroupMember,
  ConversationType,
  PresenceStatus,
  UserProfile,
} from "../../types/chat";
import "./Chat.css";
interface Props {
  user: UserProfile;
  signOut: () => void;
  signingOut: boolean;
  logoutError: string;
}
// Coordinate the chat sidebar, selected conversation, and composer.
export default function ChatPage({
  user,
  signOut,
  signingOut,
  logoutError,
}: Props) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [jumpTo, setJumpTo] = useState<{
    id: string;
    sequence: number;
    request: number;
  } | null>(null);
  const jumpVersion = useRef(0);
  const [filesId, setFilesId] = useState<string | null>(null);
  const [clearingId, setClearingId] = useState<string | null>(null);
  const clearBusy = useRef(false);
  const [clearTarget, setClearTarget] = useState<{
    id: string;
    name: string;
  } | null>(null);
  const [clearToast, setClearToast] = useState<object | null>(null);
  useEffect(() => {
    if (!clearToast) return;
    const timer = window.setTimeout(() => setClearToast(null), 3000);
    return () => window.clearTimeout(timer);
  }, [clearToast]);
  const [searchId, setSearchId] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [files, setFiles] = useState<Record<string, File[]>>({});
  // The message each conversation's composer is replying to or editing.
  const [contexts, setContexts] = useState<
    Record<string, ComposerContext | null>
  >({});
  const [messageNotice, setMessageNotice] = useState<{
    text: string;
    error: boolean;
  } | null>(null);
  const [status, setStatus] = useState<PresenceStatus>("online");
  const [note, setNote] = useState("");
  const [presenceError, setPresenceError] = useState("");
  const [openError, setOpenError] = useState("");
  const [opening, setOpening] = useState(false);
  const [groupInfo, setGroupInfo] = useState(false);
  const [creatingGroup, setCreatingGroup] = useState(false);
  const openVersion = useRef(0);
  // Track the newest status change so a slow load cannot overwrite it.
  const presenceVersion = useRef(0);
  // Signing in shows the conversation list; no conversation opens until the user picks one.
  const chat = useMessaging(selectedId, undefined, user.id);
  const { conversations, setConversations } = chat;
  useEffect(() => {
    let active = true;
    const open = async (id: string) => {
      if (!/^[0-9a-f-]{36}$/.test(id)) return;
      const version = ++openVersion.current;
      try {
        const row = await getConversation(id);
        if (!active || version !== openVersion.current) return;
        setConversations((previous) => {
          const existing = previous.find((c) => c.id === id);
          return [
            ...previous.filter((c) => c.id !== id),
            { ...existing, ...row, messages: existing?.messages ?? [] },
          ];
        });
        setSelectedId(id);
      } catch (error) {
        if (active) setOpenError((error as Error).message);
      }
    };
    const clicked = (event: Event) =>
      void open((event as CustomEvent<string>).detail);
    window.addEventListener("messenger:open-conversation", clicked);
    const requested = new URLSearchParams(window.location.search).get(
      "conversation",
    );
    if (requested) void open(requested);
    return () => {
      active = false;
      window.removeEventListener("messenger:open-conversation", clicked);
    };
  }, [setConversations]);
  // Members of the open group, for "@" suggestions and highlighted mentions.
  const [groupMembers, setGroupMembers] = useState<
    Record<string, GroupMember[]>
  >({});
  useEffect(() => {
    const version = presenceVersion.current;
    getPresence()
      .then((saved) => {
        if (version !== presenceVersion.current) return;
        setStatus(saved.status);
        setNote(saved.note);
      })
      .catch(() => {
        // Keep the default status; saving a change will report any problem.
      });
  }, []);
  // Show a status or note change at once and save it for the user's contacts.
  async function savePresence(nextStatus: PresenceStatus, nextNote: string) {
    const version = ++presenceVersion.current;
    const previous = { status, note };
    setStatus(nextStatus);
    setNote(nextNote);
    setPresenceError("");
    try {
      await updatePresence({ status: nextStatus, note: nextNote });
    } catch (error) {
      if (version !== presenceVersion.current) return;
      setStatus(previous.status);
      setNote(previous.note);
      setPresenceError((error as Error).message);
    }
  }
  // Show the unread total in the browser tab title, leaving muted chats out.
  const unreadTotal = conversations
    .filter((c) => !c.hidden && !c.muted)
    .reduce((total, c) => total + c.unread, 0);
  useEffect(() => {
    const badge = /^\(\d+\+?\)\s*/;
    const base = document.title.replace(badge, "");
    document.title = unreadTotal
      ? `(${unreadTotal > 99 ? "99+" : unreadTotal}) ${base}`
      : base;
    return () => {
      document.title = document.title.replace(badge, "");
    };
  }, [unreadTotal]);
  const profile = { ...user, online: status === "online", status, note };
  const selected = conversations.find((c) => c.id === selectedId && !c.hidden);
  // Load the open group's members, again whenever its membership changes.
  const openGroupId = selected?.type === "GROUP" ? selected.id : null;
  const openGroupSize = selected?.memberCount;
  useEffect(() => {
    if (!openGroupId) return;
    let active = true;
    getMembers(openGroupId)
      .then((found) => {
        if (active)
          setGroupMembers((previous) => ({
            ...previous,
            [openGroupId]: found,
          }));
      })
      .catch(() => {
        // Mentions still send as plain text; suggestions appear on the next change.
      });
    return () => {
      active = false;
    };
  }, [openGroupId, openGroupSize]);
  const state = selected ? chat.history[selected.id] : undefined;
  // Select a conversation; visible messages determine its read position.
  function select(id: string) {
    openVersion.current += 1;
    jumpVersion.current += 1;
    setJumpTo(null);
    setOpening(false);
    setSelectedId(id);
    setQuery("");
    setOpenError("");
    setMessageNotice(null);
    setGroupInfo(false);
    setSearchId(null);
    setFilesId(null);
  }
  // Close a conversation the user left or was removed from on another device.
  if (
    selectedId &&
    !chat.listLoading &&
    !conversations.some((c) => c.id === selectedId)
  )
    setSelectedId(null);
  // Create a group, then open it.
  async function createNewGroup(
    name: string,
    memberIds: string[],
    adminsOnly: boolean,
  ) {
    const id = await chat.newGroup(name, memberIds, adminsOnly);
    setCreatingGroup(false);
    if (id) select(id);
  }
  // Open a searched person’s direct conversation and select it.
  async function openResult(type: ConversationType, targetId: string) {
    const version = ++openVersion.current;
    setOpening(true);
    setOpenError("");
    try {
      let id: string | null;
      if (type === "GROUP") {
        const row = await updatePreferences(targetId, { hidden: false });
        if (version !== openVersion.current) return;
        setConversations((previous) => {
          const existing = previous.find((c) => c.id === row.id);
          return [
            ...previous.filter((c) => c.id !== row.id),
            { ...existing, ...row, messages: existing?.messages ?? [] },
          ];
        });
        id = row.id;
      } else id = await chat.open(targetId);
      if (version === openVersion.current && id) select(id);
    } catch (error) {
      if (version === openVersion.current)
        setOpenError((error as Error).message);
    } finally {
      if (version === openVersion.current) setOpening(false);
    }
  }
  async function clearConversation() {
    if (!clearTarget || clearBusy.current) return;
    const id = clearTarget.id;
    clearBusy.current = true;
    setMessageNotice(null);
    setClearingId(id);
    try {
      await chat.clear(id);
      setSearchId(null);
      setFilesId(null);
      setJumpTo(null);
      setContexts((previous) => ({ ...previous, [id]: null }));
      setClearToast({});
    } catch (error) {
      setMessageNotice({ text: (error as Error).message, error: true });
    } finally {
      clearBusy.current = false;
      setClearingId(null);
      setClearTarget(null);
    }
  }
  async function jumpToMessage(message: ChatMessage) {
    if (!selectedId || !message.sequence) return;
    const request = ++jumpVersion.current;
    setJumpTo({ id: message.id, sequence: message.sequence, request });
    setMessageNotice(null);
    try {
      await chat.reveal(selectedId, message.sequence);
      if (
        request === jumpVersion.current &&
        window.matchMedia?.("(max-width: 760px)").matches
      ) {
        setSearchId(null);
        setFilesId(null);
      }
    } catch (error) {
      if (request === jumpVersion.current) {
        setJumpTo(null);
        setMessageNotice({ text: (error as Error).message, error: true });
      }
    }
  }
  // Save a conversation action and close a conversation that was removed or
  // marked unread so it stays unread until the user opens it again.
  function action(id: string, value: ConversationAction) {
    const conversation = conversations.find((c) => c.id === id);
    if (!conversation) return;
    void chat.act(conversation, value);
    const markedUnread = value === "read" && conversation.unread === 0;
    if ((value === "remove" || markedUnread) && selectedId === id)
      setSelectedId(null);
  }
  // Send a message, or each file as its own message with the caption on the
  // first, clearing the draft and each file as it succeeds so a retry resumes.
  // Run a message action, reporting success or the server's reason for failure.
  async function messageAction(action: Promise<unknown>, done?: string) {
    setMessageNotice(null);
    try {
      await action;
      if (done) setMessageNotice({ text: done, error: false });
    } catch (error) {
      setMessageNotice({ text: (error as Error).message, error: true });
    }
  }
  // Nobody posts in an archived group; in an announcement group only admins do.
  function canPost(c: Conversation) {
    return !c.archived && (!c.adminsOnly || c.role === "admin");
  }
  // Wire one conversation's message menus to replies, edits, and server actions.
  function messageHandlers(id: string): MessageHandlers {
    const conversation = conversations.find((c) => c.id === id);
    return {
      onReply:
        conversation && !canPost(conversation)
          ? undefined
          : (message) =>
              setContexts((previous) => ({
                ...previous,
                [id]: { kind: "reply", message },
              })),
      onEdit: (message) => {
        setContexts((previous) => ({
          ...previous,
          [id]: { kind: "edit", message },
        }));
        setDrafts((previous) => ({ ...previous, [id]: message.text }));
      },
      onReact: (message, emoji) =>
        void messageAction(chat.react(id, message.id, emoji)),
      onForward: (message, target) =>
        void messageAction(
          chat.forward(message.id, target),
          `Forwarded to ${conversations.find((c) => c.id === target)?.name ?? "the conversation"}.`,
        ),
      onDelete: (message, everyone) => {
        // A deleted message can no longer be quoted or edited.
        if (contexts[id]?.message.id === message.id)
          setContexts((previous) => ({ ...previous, [id]: null }));
        void messageAction(chat.remove(id, message, everyone));
      },
    };
  }
  // Files are sent together as one message with the caption; an edit replaces text.
  async function send(id: string, text: string) {
    chat.updateTyping(false);
    const pending = files[id] ?? [];
    const context = contexts[id];
    const replyTo = context?.kind === "reply" ? context.message.id : undefined;
    const clearDraft = () => {
      setDrafts((previous) => ({
        ...previous,
        [id]: previous[id]?.trim() === text ? "" : previous[id],
      }));
      setContexts((previous) => ({ ...previous, [id]: null }));
    };
    if (context?.kind === "edit") {
      try {
        await chat.edit(id, context.message.id, text);
        clearDraft();
        setMessageNotice(null);
      } catch (error) {
        setMessageNotice({ text: (error as Error).message, error: true });
      }
      return;
    }
    if (!(await chat.send(id, text, pending, replyTo))) return;
    clearDraft();
    if (pending.length)
      setFiles((previous) => ({
        ...previous,
        [id]: (previous[id] ?? []).filter((f) => !pending.includes(f)),
      }));
  }
  return (
    <div className={`messenger-chat ${selected ? "has-selection" : ""}`}>
      <header className="messenger-topbar">
        <Brand />
        <span>Your team, in sync.</span>
        <ChatSettings
          signOut={signOut}
          signingOut={signingOut}
          logoutError={logoutError}
          user={profile}
          onStatusChange={(value) => void savePresence(value, note)}
          onNoteChange={(value) => void savePresence(status, value)}
          presenceError={presenceError}
        />
      </header>
      <div className="messenger-columns">
        <ChatSidebar
          user={profile}
          conversations={conversations}
          selectedId={selectedId}
          query={query}
          onQuery={setQuery}
          onSelect={select}
          onResult={openResult}
          onNewGroup={() => setCreatingGroup(true)}
          groupPanel={
            creatingGroup && (
              <NewGroup
                onCreate={createNewGroup}
                onCancel={() => setCreatingGroup(false)}
              />
            )
          }
          onAction={action}
          loading={chat.listLoading}
          error={chat.listError}
          onRetry={() => void chat.refreshList()}
          onMore={
            chat.nextCursor === null
              ? undefined
              : () => void chat.refreshList(chat.nextCursor!)
          }
          opening={opening}
          openError={openError}
          actionError={chat.actionError}
        />
        <main className="conversation-main">
          {selected ? (
            <>
              <ChatHeader
                conversation={selected}
                onBack={() => setSelectedId(null)}
                onAction={action}
                onGroupInfo={() => setGroupInfo(!groupInfo)}
                clearing={clearingId === selected.id}
                onClearChat={() =>
                  setClearTarget({ id: selected.id, name: selected.name })
                }
                onSharedFiles={() => {
                  setSearchId(null);
                  setFilesId(filesId === selected.id ? null : selected.id);
                }}
                searching={searchId === selected.id}
                onSearch={() => {
                  setFilesId(null);
                  setSearchId(searchId === selected.id ? null : selected.id);
                }}
              />
              <div className="conversation-workspace">
                <div className="conversation-thread">
                  {groupInfo && selected.type === "GROUP" && (
                    <GroupInfo
                      // Distinct from the composer's key: siblings with the same key
                      // stop React from removing this panel when it closes.
                      key={`group-info-${selected.id}`}
                      conversation={selected}
                      userId={user.id}
                      onChanged={() => void chat.refreshList()}
                      onLeave={async () => {
                        await chat.leave(selected.id);
                        setGroupInfo(false);
                        setSelectedId(null);
                      }}
                      onClose={() => setGroupInfo(false)}
                    />
                  )}
                  {state?.error && (
                    <p className="messaging-notice" role="alert">
                      {state.error} Retrying automatically.
                    </p>
                  )}
                  {!state?.loaded ? (
                    <p className="messaging-notice" role="status">
                      {state?.error
                        ? "Message history is unavailable."
                        : "Loading messages…"}
                    </p>
                  ) : (
                    <MessageList
                      key={`messages-${selected.id}`}
                      onVisible={chat.readVisible}
                      hasOlder={state?.older}
                      loadingOlder={state?.loading}
                      onOlder={() => void chat.older(selected.id)}
                      jumpTo={jumpTo}
                      showTimestamps
                      conversation={selected}
                      userId={user.id}
                      forwardTargets={conversations
                        .filter(
                          (c) =>
                            !c.hidden && c.id !== selected.id && canPost(c),
                        )
                        .map((c) => ({ id: c.id, name: c.name }))}
                      handlers={messageHandlers(selected.id)}
                      username={user.username}
                    />
                  )}
                  {chat.isTyping && (
                    <p className="messaging-notice" role="status">
                      {selected.type === "GROUP" ? "Someone" : selected.name} is
                      typing…
                    </p>
                  )}
                  {messageNotice && (
                    <p
                      className="messaging-notice"
                      role={messageNotice.error ? "alert" : "status"}
                    >
                      {messageNotice.text}
                    </p>
                  )}
                  {!canPost(selected) ? (
                    <p
                      className="messaging-notice announcement-notice"
                      role="status"
                    >
                      {selected.archived
                        ? "An administrator archived this group. You can read it, but no new messages can be sent."
                        : "Only admins can send messages in this group."}
                    </p>
                  ) : (
                    <MessageComposer
                      key={selected.id}
                      name={selected.name}
                      draft={drafts[selected.id] ?? ""}
                      files={files[selected.id] ?? []}
                      context={contexts[selected.id] ?? null}
                      onCancelContext={() => {
                        if (contexts[selected.id]?.kind === "edit")
                          setDrafts((previous) => ({
                            ...previous,
                            [selected.id]: "",
                          }));
                        setContexts((previous) => ({
                          ...previous,
                          [selected.id]: null,
                        }));
                      }}
                      onFilesChange={(picked) =>
                        setFiles((previous) => ({
                          ...previous,
                          [selected.id]: picked,
                        }))
                      }
                      pending={chat.sending[selected.id] ?? false}
                      error={chat.sendErrors[selected.id] ?? ""}
                      disabled={!state?.loaded}
                      onBlur={() => chat.updateTyping(false)}
                      onDraftChange={(value) => {
                        chat.updateTyping(value.trim().length > 0);
                        setDrafts((previous) => ({
                          ...previous,
                          [selected.id]: value,
                        }));
                      }}
                      onSend={(text) => void send(selected.id, text)}
                      mentionable={(groupMembers[selected.id] ?? []).filter(
                        (m) => m.id !== user.id,
                      )}
                    />
                  )}
                </div>
                {filesId === selected.id && (
                  <SharedFiles
                    key={`files-${selected.id}-${selected.historyFromSequence ?? 0}`}
                    conversationId={selected.id}
                    name={selected.name}
                    onClose={() => setFilesId(null)}
                    onSelect={(message) => void jumpToMessage(message)}
                  />
                )}
                {searchId === selected.id && (
                  <MessageSearch
                    key={`search-${selected.id}-${selected.historyFromSequence ?? 0}`}
                    conversationId={selected.id}
                    name={selected.name}
                    onSelect={(message) => void jumpToMessage(message)}
                    activeId={jumpTo?.id}
                    onClose={() => setSearchId(null)}
                  />
                )}
              </div>
            </>
          ) : (
            <EmptyChatState />
          )}
          <p className="chat-preview-notice">
            Messages, pins, mutes, read markers, your status and your note are
            saved to your account and refresh automatically.
          </p>
        </main>
      </div>
      {clearTarget && (
        <ClearChatDialog
          name={clearTarget.name}
          pending={clearingId !== null}
          onCancel={() => {
            if (!clearBusy.current) setClearTarget(null);
          }}
          onConfirm={() => void clearConversation()}
        />
      )}
      {clearToast && (
        <div className="chat-toast" role="status" aria-live="polite">
          <span aria-hidden="true">✓</span> Chat is cleared
        </div>
      )}
    </div>
  );
}
