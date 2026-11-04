import { useEffect, useState } from "react";
import Avatar from "./Avatar";
import PeoplePicker from "./PeoplePicker";
import BulkAdd from "./BulkAdd";
import {
  addMembers,
  getMembers,
  removeMember,
  renameGroup,
  setAdminsOnly,
  setMemberRole,
} from "../../services/chatService";
import type { Conversation, GroupMember } from "../../types/chat";

// Show a group's members over the whole chat window; admins can rename it and
// manage members, anyone can leave.
export default function GroupInfo({
  conversation: c,
  userId,
  onChanged,
  onLeave,
  onClose,
}: {
  conversation: Conversation;
  userId: string;
  onChanged: () => void;
  onLeave: () => Promise<void>;
  onClose: () => void;
}) {
  const [members, setMembers] = useState<GroupMember[] | null>(null);
  const [name, setName] = useState(c.name);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState("");
  const admin = c.role === "admin";
  // Reload the members whenever the group changes, including from other devices.
  useEffect(() => {
    let active = true;
    getMembers(c.id)
      .then((found) => {
        if (active) setMembers(found);
      })
      .catch((failure) => {
        if (active) setError((failure as Error).message);
      });
    return () => {
      active = false;
    };
  }, [c.id, c.memberCount, c.role, c.name]);
  // Run a change, show its result, and let the list refresh the group's summary.
  async function change(action: () => Promise<GroupMember[] | unknown>) {
    setBusy(true);
    setError("");
    try {
      const result = await action();
      if (Array.isArray(result)) setMembers(result);
      onChanged();
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const count = members?.length ?? c.memberCount ?? 0;
  const visibleMembers = members?.filter((member) =>
    `${member.name} ${member.username}`
      .toLowerCase()
      .includes(search.trim().toLowerCase()),
  );
  return (
    <section
      className="group-info"
      aria-label="Group info"
      onKeyDown={(e) => {
        if (e.key === "Escape") onClose();
      }}
    >
      <header className="group-info-bar">
        <button
          type="button"
          className="icon-button group-info-close"
          aria-label="Close group info"
          autoFocus
          onClick={onClose}
        >
          ×
        </button>
        <div>
          <strong>Group details</strong>
          <small>People, preferences, and permissions</small>
        </div>
      </header>
      <div className="group-info-body">
        <div className="group-info-hero">
          <span className="group-info-avatar">
            <Avatar name={c.name} group />
          </span>
          <div className="group-info-heading">
            <span className="group-info-eyebrow">
              {c.adminsOnly ? "Announcements" : "Group conversation"}
            </span>
            <h2>{c.name}</h2>
            <p>
              {count} members ·{" "}
              {members?.filter((m) => m.role === "admin").length ?? "…"} admins
            </p>
          </div>
          <span className="group-role-badge">
            {admin ? "You’re an admin" : "You’re a member"}
          </span>
        </div>
        {error && (
          <p className="group-info-error" role="alert">
            {error}
          </p>
        )}
        <div className="group-info-layout">
          <div className="group-info-overview">
            <div className="group-card group-about">
              <h3>About this group</h3>
              <dl>
                <div>
                  <dt>Conversation type</dt>
                  <dd>{c.adminsOnly ? "Announcements" : "Group chat"}</dd>
                </div>
                <div>
                  <dt>Who can post</dt>
                  <dd>{c.adminsOnly ? "Admins only" : "All members"}</dd>
                </div>
                <div>
                  <dt>Your role</dt>
                  <dd>{admin ? "Admin" : "Member"}</dd>
                </div>
              </dl>
            </div>
            {admin && (
              <div className="group-card">
                <h3>Group settings</h3>
                <form
                  className="group-rename"
                  onSubmit={(e) => {
                    e.preventDefault();
                    if (name.trim() && name.trim() !== c.name)
                      void change(() => renameGroup(c.id, name.trim()));
                  }}
                >
                  <label htmlFor="group-rename">Group name</label>
                  <div>
                    <input
                      id="group-rename"
                      maxLength={100}
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                    />
                    <button
                      type="submit"
                      disabled={busy || !name.trim() || name.trim() === c.name}
                    >
                      Rename
                    </button>
                  </div>
                </form>
                <label className="group-option">
                  <input
                    type="checkbox"
                    checked={!!c.adminsOnly}
                    disabled={busy}
                    onChange={(e) => {
                      const value = e.target.checked;
                      void change(() => setAdminsOnly(c.id, value));
                    }}
                  />
                  <span>
                    <strong>Announcement group</strong>
                    <small>Only admins can send messages</small>
                  </span>
                </label>
              </div>
            )}
            {!admin && c.adminsOnly && (
              <p className="group-card group-note">
                Only admins can send messages in this group.
              </p>
            )}
            <button
              type="button"
              className="group-leave"
              disabled={busy}
              onClick={() => {
                if (window.confirm(`Leave ${c.name}?`)) void change(onLeave);
              }}
            >
              Leave group
            </button>
          </div>
          <div className="group-info-people">
            <div className="group-card group-members-card">
              <div className="group-section-heading">
                <h3>
                  Members <span>{count}</span>
                </h3>
                <small>People in this conversation</small>
              </div>
              <label
                className="group-member-search"
                htmlFor="group-member-search"
              >
                <span>Find a member</span>
                <input
                  id="group-member-search"
                  type="search"
                  placeholder="Search by name or username"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
              </label>
              {!members ? (
                <p role="status">Loading members…</p>
              ) : (
                <ul className="group-members">
                  {visibleMembers?.map((m) => (
                    <li key={m.id}>
                      <Avatar name={m.name} />
                      <span className="group-member-name">
                        <strong>
                          {m.id === userId ? `${m.name} (you)` : m.name}
                        </strong>
                        <small>@{m.username}</small>
                      </span>
                      <span
                        className={`group-role-badge ${m.role === "member" ? "group-role-member" : ""}`}
                      >
                        {m.role === "admin" ? "Admin" : "Member"}
                      </span>
                      {admin && m.id !== userId && (
                        <span className="group-member-actions">
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() =>
                              void change(() =>
                                setMemberRole(
                                  c.id,
                                  m.id,
                                  m.role === "admin" ? "member" : "admin",
                                ),
                              )
                            }
                          >
                            {m.role === "admin" ? "Remove admin" : "Make admin"}
                          </button>
                          <button
                            type="button"
                            className="danger"
                            disabled={busy}
                            aria-label={`Remove ${m.name} from the group`}
                            onClick={() => {
                              if (
                                window.confirm(
                                  `Remove ${m.name} from ${c.name}?`,
                                )
                              )
                                void change(() => removeMember(c.id, m.id));
                            }}
                          >
                            Remove
                          </button>
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              {members && visibleMembers?.length === 0 && (
                <p className="group-member-empty">
                  No members match “{search}”. Try another name.
                </p>
              )}
            </div>
            {admin && members && (
              <div className="group-card group-add-card">
                <h3>Add people</h3>
                <p>Invite someone to join the conversation.</p>
                <PeoplePicker
                  id="group-add-people"
                  exclude={members.map((m) => m.id)}
                  onPick={(person) =>
                    void change(() => addMembers(c.id, [person.id]))
                  }
                />
                <BulkAdd
                  id="group-bulk-add"
                  exclude={members.map((m) => m.id)}
                  limit={100 - members.length}
                  onAdd={async (people) => {
                    setMembers(
                      await addMembers(
                        c.id,
                        people.map((p) => p.id),
                      ),
                    );
                    onChanged();
                  }}
                />
              </div>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
