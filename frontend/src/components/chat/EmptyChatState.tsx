// Prompt the user to choose or start a conversation.
export default function EmptyChatState() {
  return (
    <div className="empty-chat">
      <span aria-hidden="true">✦</span>
      <h1>
        A little less email.
        <br />A lot more together.
      </h1>
      <p>Search for a person or select a conversation to start messaging.</p>
    </div>
  );
}
