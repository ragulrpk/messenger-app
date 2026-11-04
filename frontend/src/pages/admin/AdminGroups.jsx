import { useEffect, useState } from "react";
import {
  addGroupMembers,
  createGroup,
  getGroup,
  listGroups,
  listPeople,
  removeGroupMember,
  renameGroup,
  setGroupAnnouncements,
  setGroupArchived,
  setGroupRole,
} from "../../services/adminService";
import useAdminAction from "./useAdminAction";

// Finds active people to add to a group. It has no form of its own because it
// sits inside the new-group form; Enter searches instead of submitting.
function PersonPicker({ id, label, run, pending, onPick, exclude }) {
  const [query, setQuery] = useState("");
  const [found, setFound] = useState([]);
  async function search() {
    const page = await run(() => listPeople(query));
    if (page) setFound(page.items.filter((p) => p.enabled));
  }
  return (
    <div className="password-field" role="search">
      <label htmlFor={id}>{label}</label>
      <div className="admin-search">
        <input
          id={id}
          type="search"
          maxLength={100}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            void search();
          }}
        />
        <button type="button" disabled={pending} onClick={() => void search()}>
          Search
        </button>
      </div>
      <ul className="admin-accounts">
        {found
          .filter((p) => !exclude.has(p.id))
          .map((p) => (
            <li key={p.id}>
              <button
                type="button"
                className="admin-row"
                disabled={pending}
                onClick={() => onPick(p)}
                aria-label={`Add ${p.name}`}
              >
                + <strong>{p.name}</strong>{" "}
                <span className="admin-username">
                  {p.gpfNumber ? `GPF ${p.gpfNumber}` : `@${p.loginId}`}
                </span>
              </button>
            </li>
          ))}
      </ul>
    </div>
  );
}

// Groups across the workspace: create them for others, manage members and
// group admins, rename, announcement mode, and archive (read-only) or restore.
export default function AdminGroups() {
  const { run, pending, error, setError } = useAdminAction();
  const [query, setQuery] = useState("");
  const [page, setPage] = useState({ items: [], hasMore: false });
  const [detail, setDetail] = useState(null);
  const [name, setName] = useState("");
  const [draft, setDraft] = useState({
    name: "",
    adminsOnly: false,
    members: [],
    admins: new Set(),
  });

  useEffect(() => {
    let active = true;
    listGroups("").then(
      (found) => active && setPage(found),
      (failure) => active && setError(failure.message),
    );
    return () => {
      active = false;
    };
  }, [setError]);

  async function search(event) {
    event?.preventDefault();
    const found = await run(() => listGroups(query));
    if (found) setPage(found);
  }

  function show(result) {
    if (!result) return;
    setDetail(result);
    setName(result.group.name);
    setPage((previous) => ({
      ...previous,
      items: previous.items.some((g) => g.id === result.group.id)
        ? previous.items.map((g) =>
            g.id === result.group.id ? result.group : g,
          )
        : [result.group, ...previous.items],
    }));
  }

  async function act(prompt, work) {
    if (prompt && !window.confirm(prompt)) return;
    show(await run(work));
  }

  async function create(event) {
    event.preventDefault();
    const result = await run(() =>
      createGroup({
        name: draft.name,
        adminsOnly: draft.adminsOnly,
        memberIds: draft.members.map((p) => p.id),
        adminIds: draft.members
          .filter((p) => draft.admins.has(p.id))
          .map((p) => p.id),
      }),
    );
    if (!result) return;
    show(result);
    setDraft({ name: "", adminsOnly: false, members: [], admins: new Set() });
  }

  const group = detail?.group;
  const memberIds = new Set(detail?.members.map((m) => m.id) ?? []);

  return (
    <>
      {error && (
        <p className="admin-error" role="alert">
          {error}
        </p>
      )}
      <section className="profile-section">
        <header>
          <h2>Groups</h2>
          <p>Every group in the workspace, most recently active first.</p>
        </header>
        <div className="password-form">
          <form className="password-field" role="search" onSubmit={search}>
            <label htmlFor="admin-group-search">Search by name</label>
            <input
              id="admin-group-search"
              type="search"
              maxLength={100}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </form>
          <ul className="admin-accounts">
            {page.items.map((g) => (
              <li key={g.id}>
                <button
                  type="button"
                  className="admin-row"
                  onClick={() => act(null, () => getGroup(g.id))}
                  aria-label={`Open group ${g.name}`}
                >
                  <strong>{g.name}</strong>{" "}
                  <span className="admin-username">
                    {g.memberCount} members
                  </span>
                  <span className="admin-badges">
                    {g.adminsOnly && <span>Announcements</span>}
                    {g.archived && <span>Archived</span>}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </section>

      {group && (
        <section className="profile-section" aria-label="Group details">
          <header>
            <h2>{group.name}</h2>
            <p>
              {group.memberCount} members
              {group.archived ? " · archived: read-only" : ""}
            </p>
          </header>
          <form
            className="password-form"
            onSubmit={(event) => {
              event.preventDefault();
              void act(null, () => renameGroup(group.id, name));
            }}
          >
            <div className="password-field">
              <label htmlFor="admin-group-name">Group name</label>
              <input
                id="admin-group-name"
                maxLength={100}
                value={name}
                disabled={pending}
                onChange={(event) => setName(event.target.value)}
              />
            </div>
            <div className="password-actions">
              <button type="submit" disabled={pending}>
                Rename
              </button>
              <button
                type="button"
                disabled={pending}
                onClick={() =>
                  act(null, () =>
                    setGroupAnnouncements(group.id, !group.adminsOnly),
                  )
                }
              >
                {group.adminsOnly
                  ? "Let everyone post"
                  : "Only group admins post"}
              </button>
              <button
                type="button"
                disabled={pending}
                onClick={() =>
                  act(
                    group.archived
                      ? null
                      : `Archive ${group.name}? Members keep the history but nobody can post.`,
                    () => setGroupArchived(group.id, !group.archived),
                  )
                }
              >
                {group.archived ? "Restore group" : "Archive group"}
              </button>
            </div>
          </form>
          <div className="password-form">
            <ul className="admin-accounts" aria-label="Members">
              {detail.members.map((m) => (
                <li key={m.id}>
                  <div>
                    <strong>{m.name}</strong>{" "}
                    <span className="admin-username">@{m.username}</span>
                    <span className="admin-badges">
                      {m.role === "admin" && <span>Group admin</span>}
                    </span>
                  </div>
                  <div className="password-actions">
                    <button
                      type="button"
                      disabled={pending}
                      onClick={() =>
                        act(null, () =>
                          setGroupRole(
                            group.id,
                            m.id,
                            m.role === "admin" ? "member" : "admin",
                          ),
                        )
                      }
                    >
                      {m.role === "admin" ? "Make member" : "Make group admin"}
                    </button>
                    <button
                      type="button"
                      disabled={pending}
                      onClick={() =>
                        act(`Remove ${m.name} from ${group.name}?`, () =>
                          removeGroupMember(group.id, m.id),
                        )
                      }
                    >
                      Remove
                    </button>
                  </div>
                </li>
              ))}
            </ul>
            <PersonPicker
              id="admin-group-add"
              label="Add people"
              run={run}
              pending={pending}
              exclude={memberIds}
              onPick={(p) => act(null, () => addGroupMembers(group.id, [p.id]))}
            />
          </div>
        </section>
      )}

      <section className="profile-section">
        <header>
          <h2>New group</h2>
          <p>Choose at least two people and one group admin among them.</p>
        </header>
        <form className="password-form" noValidate onSubmit={create}>
          <div className="password-field">
            <label htmlFor="admin-new-group-name">Group name</label>
            <input
              id="admin-new-group-name"
              maxLength={100}
              value={draft.name}
              disabled={pending}
              onChange={(event) =>
                setDraft((previous) => ({
                  ...previous,
                  name: event.target.value,
                }))
              }
            />
          </div>
          <label className="password-visibility">
            <input
              type="checkbox"
              checked={draft.adminsOnly}
              onChange={(event) =>
                setDraft((previous) => ({
                  ...previous,
                  adminsOnly: event.target.checked,
                }))
              }
            />
            Announcements: only group admins post
          </label>
          <ul className="admin-accounts" aria-label="Chosen people">
            {draft.members.map((p) => (
              <li key={p.id}>
                <div>
                  <strong>{p.name}</strong>
                </div>
                <div className="password-actions">
                  <label className="password-visibility">
                    <input
                      type="checkbox"
                      checked={draft.admins.has(p.id)}
                      onChange={(event) =>
                        setDraft((previous) => {
                          const admins = new Set(previous.admins);
                          if (event.target.checked) admins.add(p.id);
                          else admins.delete(p.id);
                          return { ...previous, admins };
                        })
                      }
                    />
                    Group admin
                  </label>
                  <button
                    type="button"
                    onClick={() =>
                      setDraft((previous) => ({
                        ...previous,
                        members: previous.members.filter((m) => m.id !== p.id),
                      }))
                    }
                  >
                    Remove
                  </button>
                </div>
              </li>
            ))}
          </ul>
          <PersonPicker
            id="admin-new-group-people"
            label="Find people"
            run={run}
            pending={pending}
            exclude={new Set(draft.members.map((p) => p.id))}
            onPick={(p) =>
              setDraft((previous) => ({
                ...previous,
                members: [...previous.members, p],
                admins: previous.members.length
                  ? previous.admins
                  : new Set([p.id]),
              }))
            }
          />
          <div className="password-actions">
            <button
              type="submit"
              disabled={
                pending ||
                !draft.name.trim() ||
                draft.members.length < 2 ||
                draft.admins.size === 0
              }
            >
              Create group
            </button>
          </div>
        </form>
      </section>
    </>
  );
}
