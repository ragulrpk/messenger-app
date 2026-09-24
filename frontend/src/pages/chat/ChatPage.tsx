import { useCallback, useRef, useState } from "react";
import Brand from "../../components/Brand";
import ChatSettings from "../../components/chat/ChatSettings";
import ChatSidebar from "../../components/chat/ChatSidebar";
import ChatHeader from "../../components/chat/ChatHeader";
import MessageList from "../../components/chat/MessageList";
import MessageComposer from "../../components/chat/MessageComposer";
import EmptyChatState from "../../components/chat/EmptyChatState";
import { applyAction } from "../../components/chat/chatState";
import useMessaging from "./useMessaging";
import type {
  ConversationAction,
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
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [status, setStatus] = useState<PresenceStatus>("online");
  const [note, setNote] = useState("");
  const [openError, setOpenError] = useState("");
  const [opening, setOpening] = useState(false);
  const openVersion = useRef(0);
  // Select the first loaded conversation when none is selected.
  const selectInitial = useCallback((id: string) => {
    setSelectedId((current) => current ?? id);
  }, []);
  const chat = useMessaging(selectedId, selectInitial);
  const { conversations, setConversations } = chat;
  const profile = { ...user, online: status === "online", status, note };
  const selected = conversations.find((c) => c.id === selectedId && !c.hidden);
  const state = selected ? chat.history[selected.id] : undefined;
  // Select a conversation and clear its unread marker.
  function select(id: string) {
    openVersion.current += 1;
    setOpening(false);
    setSelectedId(id);
    setQuery("");
    setOpenError("");
    setConversations((previous) =>
      previous.map((c) => (c.id === id ? { ...c, unread: 0 } : c)),
    );
  }
  // Open a searched person’s direct conversation and select it.
  async function openResult(_type: ConversationType, targetId: string) {
    const version = ++openVersion.current;
    setOpening(true);
    setOpenError("");
    try {
      const id = await chat.open(targetId);
      if (version === openVersion.current && id) select(id);
    } catch (error) {
      if (version === openVersion.current)
        setOpenError((error as Error).message);
    } finally {
      if (version === openVersion.current) setOpening(false);
    }
  }
  // Apply a conversation action and clear a removed selection.
  function action(id: string, value: ConversationAction) {
    setConversations((previous) => applyAction(previous, id, value));
    if (value === "remove" && selectedId === id) setSelectedId(null);
  }
  // Send a message and clear its draft after success.
  async function send(id: string, text: string) {
    if (await chat.send(id, text)) {
      setDrafts((previous) => ({
        ...previous,
        [id]: previous[id]?.trim() === text ? "" : previous[id],
      }));
    }
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
          onStatusChange={setStatus}
          onNoteChange={setNote}
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
        />
        <main className="conversation-main">
          {selected ? (
            <>
              <ChatHeader
                conversation={selected}
                onBack={() => setSelectedId(null)}
                onAction={action}
              />
              {state?.error && (
                <p className="messaging-notice" role="alert">
                  {state.error} Retrying automatically.
                </p>
              )}
              {state?.older && (
                <button
                  className="history-more"
                  disabled={state.loading}
                  onClick={() => void chat.older(selected.id)}
                >
                  Load older messages
                </button>
              )}
              {!state?.loaded ? (
                <p className="messaging-notice" role="status">
                  {state?.error
                    ? "Message history is unavailable."
                    : "Loading messages…"}
                </p>
              ) : (
                <MessageList
                  showTimestamps
                  conversation={selected}
                  userId={user.id}
                />
              )}
              <MessageComposer
                key={selected.id}
                name={selected.name}
                draft={drafts[selected.id] ?? ""}
                pending={chat.sending[selected.id] ?? false}
                error={chat.sendErrors[selected.id] ?? ""}
                disabled={!state?.loaded}
                onDraftChange={(value) =>
                  setDrafts((previous) => ({
                    ...previous,
                    [selected.id]: value,
                  }))
                }
                onSend={(text) => void send(selected.id, text)}
              />
            </>
          ) : (
            <EmptyChatState />
          )}
          <p className="chat-preview-notice">
            Messages are saved and refresh automatically. Pin, mute, read
            markers, status and notes apply only in this session.
          </p>
        </main>
      </div>
    </div>
  );
}
