import { statusLabels } from "./presence";
import Avatar from "./Avatar";
import type { User } from "../../types/chat";
// Show the current user’s avatar, presence, and note.
export default function CurrentUserHeader({ user }: { user: User }) {
  return (
    <header className="current-user">
      <Avatar
        name={user.name}
        online={user.online}
        status={user.status}
        url={user.avatarUrl}
      />
      <div className="truncate">
        <strong title={user.name}>{user.name}</strong>
        <small>
          {statusLabels[user.status ?? (user.online ? "online" : "offline")]} ·
          Your workspace
        </small>
        {user.note && (
          <p className="current-user-note" title={user.note}>
            {user.note}
          </p>
        )}
      </div>
    </header>
  );
}
