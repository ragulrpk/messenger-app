import { Link } from "react-router-dom";
import { useTheme, setTheme } from "../../context/theme";
import { useState } from "react";
import Avatar from "./Avatar";
import { statusLabels } from "./presence";
import type { PresenceStatus, User } from "../../types/chat";

interface Props {
  user: User & { username?: string };
  onStatusChange: (value: PresenceStatus) => void;
  onNoteChange: (value: string) => void;
  signOut: () => void;
  signingOut: boolean;
  logoutError: string;
}
// Render account, presence, note, theme, and sign-out controls.
export default function ChatSettings({
  user,
  onStatusChange,
  onNoteChange,
  signOut,
  signingOut,
  logoutError,
}: Props) {
  const [editingNote, setEditingNote] = useState(false);
  const [noteDraft, setNoteDraft] = useState("");
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
            to="/admin/password-reset"
            onClick={(event) => {
              const menu = event.currentTarget.closest("details");
              if (menu) menu.open = false;
            }}
          >
            Reset a user’s password
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
              setTheme(e.target.value === "dark" ? "dark" : "light")
            }
          >
            <option value="light">Light</option>
            <option value="dark">Dark</option>
          </select>
        </label>
        <small>Status and notes apply to this local session.</small>
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
