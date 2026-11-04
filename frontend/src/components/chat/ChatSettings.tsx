import { Link } from "react-router-dom";
import { useTheme, setTheme, isTheme } from "../../context/theme";
import { useEffect, useState } from "react";
import Avatar from "./Avatar";
import { statusLabels } from "./presence";
import {
  notificationState,
  backgroundNotificationState,
  configureBackgroundNotifications,
  requestNotifications,
} from "../../services/notifications";
import type { PresenceStatus, User } from "../../types/chat";

interface Props {
  user: User & { username?: string };
  onStatusChange: (value: PresenceStatus) => void;
  onNoteChange: (value: string) => void;
  presenceError?: string;
  signOut: () => void;
  signingOut: boolean;
  logoutError: string;
}
// Render account, presence, note, notification, theme, and sign-out controls.
export default function ChatSettings({
  user,
  onStatusChange,
  onNoteChange,
  presenceError,
  signOut,
  signingOut,
  logoutError,
}: Props) {
  const [editingNote, setEditingNote] = useState(false);
  const [noteDraft, setNoteDraft] = useState("");
  const [notifications, setNotifications] = useState(notificationState);
  const [background, setBackground] = useState(backgroundNotificationState);
  useEffect(() => {
    const changed = () => setBackground(backgroundNotificationState());
    window.addEventListener("messenger:push-status", changed);
    return () => window.removeEventListener("messenger:push-status", changed);
  }, []);
  const theme = useTheme();
  const status = user.status ?? "online";
  return (
    <details
      className="chat-settings"
      onKeyDown={(e) => {
        if (e.key === "Escape") {
          e.currentTarget.open = false;
          e.currentTarget.querySelector("summary")?.focus();
        }
      }}
    >
      <summary aria-label="Chat settings" title="Chat settings">
        ⚙
      </summary>
      <div className="settings-panel">
        <strong>Settings</strong>
        <div className="settings-identity">
          <Avatar name={user.name} status={status} url={user.avatarUrl} />
          <div>
            <strong>{user.name}</strong>
            <small>{statusLabels[status]}</small>
          </div>
        </div>
        <Link
          className="settings-link profile-page-link"
          to="/profile"
          onClick={(e) => {
            const menu = e.currentTarget.closest("details");
            if (menu) menu.open = false;
          }}
        >
          View profile
        </Link>
        <Link
          className="settings-link"
          to="/profile#password"
          onClick={(event) => {
            const menu = event.currentTarget.closest("details");
            if (menu) menu.open = false;
          }}
        >
          Change password
        </Link>
        {user.administrator === true && (
          <Link
            className="settings-link"
            to="/admin"
            onClick={(event) => {
              const menu = event.currentTarget.closest("details");
              if (menu) menu.open = false;
            }}
          >
            Administration
          </Link>
        )}
        <label className="status-setting" htmlFor="my-status">
          Your status
          <select
            id="my-status"
            value={status}
            onChange={(e) => onStatusChange(e.target.value as PresenceStatus)}
          >
            {Object.entries(statusLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <div className="personal-note">
          <strong>Personal note</strong>
          {user.note ? (
            <p className="saved-note">{user.note}</p>
          ) : (
            <p className="note-placeholder">
              Add a note about your day or availability.
            </p>
          )}
          {editingNote ? (
            <div className="note-editor">
              <label htmlFor="my-note">Your note</label>
              <textarea
                id="my-note"
                maxLength={160}
                rows={3}
                value={noteDraft}
                onChange={(e) => setNoteDraft(e.target.value)}
              />
              <small>{noteDraft.length}/160</small>
              <div>
                <button
                  type="button"
                  onClick={() => {
                    onNoteChange(noteDraft.trim());
                    setEditingNote(false);
                  }}
                >
                  Save note
                </button>
                <button type="button" onClick={() => setEditingNote(false)}>
                  Cancel
                </button>
              </div>
            </div>
          ) : (
            <div>
              <button
                type="button"
                className="settings-link"
                onClick={() => {
                  setNoteDraft(user.note ?? "");
                  setEditingNote(true);
                }}
              >
                {user.note ? "Edit note" : "Add a note"}
              </button>
              {user.note && (
                <button
                  type="button"
                  className="settings-link"
                  onClick={() => onNoteChange("")}
                >
                  Remove note
                </button>
              )}
            </div>
          )}
        </div>
        <label className="theme-setting" htmlFor="app-theme">
          Theme
          <select
            id="app-theme"
            value={theme}
            onChange={(e) =>
              isTheme(e.target.value) && setTheme(e.target.value)
            }
          >
            <option value="light">Light</option>
            <option value="dark">Dark</option>
            <option value="ocean">Ocean Blue</option>
            <option value="forest">Forest Green</option>
          </select>
        </label>
        {presenceError && (
          <p className="chat-error" role="alert">
            {presenceError} Your status was not saved.
          </p>
        )}
        <small>People you chat with can see your status and note.</small>
        {notifications !== "unsupported" && (
          <div className="notification-setting">
            <strong>Desktop notifications</strong>
            {notifications === "default" ? (
              <button
                type="button"
                className="settings-link"
                onClick={() =>
                  void requestNotifications(user.id).then(setNotifications)
                }
              >
                Turn on notifications
              </button>
            ) : (
              <small>
                {notifications === "granted"
                  ? "On. Mute a conversation to silence it."
                  : "Blocked. Allow notifications for this site in your browser settings."}
              </small>
            )}
            {notifications === "granted" && (
              <>
                <small>
                  {background === "enabled"
                    ? "Background notifications are on, even when this app is closed."
                    : background === "unconfigured"
                      ? "Background notifications are unavailable on this server."
                      : background === "unsupported"
                        ? "This browser supports desktop alerts only."
                        : background === "error"
                          ? "Background notifications could not be enabled. Please retry."
                          : "Enable background notifications to receive alerts when this app is closed."}
                </small>
                {(background === "off" || background === "error") && (
                  <button
                    className="settings-link"
                    onClick={() =>
                      void configureBackgroundNotifications(user.id, true)
                    }
                  >
                    Enable background notifications
                  </button>
                )}
              </>
            )}
          </div>
        )}
        <div className="settings-account-actions">
          <a
            href="https://tnmail.gov.in/"
            target="_blank"
            rel="noopener noreferrer"
            aria-label="Open TN Mail (opens in a new tab)"
          >
            Open TN Mail <span aria-hidden="true">↗</span>
          </a>
          <button type="button" onClick={signOut} disabled={signingOut}>
            {signingOut ? "Signing out…" : "Sign out"}
          </button>
          {logoutError && (
            <p className="chat-error" role="alert">
              {logoutError}
            </p>
          )}
        </div>
      </div>
    </details>
  );
}
