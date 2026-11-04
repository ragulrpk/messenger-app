import { useEffect, useState } from "react";
import {
  bulkAddPeople,
  createPerson,
  getPerson,
  listPeople,
  requirePasswordReset,
  setPersonAdministrator,
  setPersonEnabled,
  signOutPerson,
  updatePerson,
} from "../../services/adminService";
import useAdminAction from "./useAdminAction";

const EMPTY = {
  hasGpf: true,
  gpfNumber: "",
  username: "",
  name: "",
  email: "",
  mobile: "",
  cadre: "",
};

function TextField({ id, label, value, onChange, disabled, ...input }) {
  return (
    <div className="password-field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        {...input}
      />
    </div>
  );
}

// People: search, details and sign-in history, adding (one or from a CSV file)
// and account actions. New people get a temporary password shown once; their
// first sign-in needs a code sent to their own email, so it is safe to hand over.
export default function AdminPeople({ self }) {
  const { run, pending, error, setError } = useAdminAction();
  const [query, setQuery] = useState("");
  const [page, setPage] = useState({ items: [], hasMore: false });
  const [selected, setSelected] = useState(null);
  const [edit, setEdit] = useState(null);
  const [draft, setDraft] = useState(EMPTY);
  const [issued, setIssued] = useState(null);
  const [report, setReport] = useState(null);
  const [file, setFile] = useState(null);

  useEffect(() => {
    let active = true;
    listPeople("").then(
      (found) => active && setPage(found),
      (failure) => active && setError(failure.message),
    );
    return () => {
      active = false;
    };
  }, [setError]);

  async function search(event) {
    event?.preventDefault();
    const found = await run(() => listPeople(query));
    if (found) setPage(found);
  }

  async function more() {
    const found = await run(() => listPeople(query, page.items.length));
    if (found)
      setPage((previous) => ({
        items: [...previous.items, ...found.items],
        hasMore: found.hasMore,
      }));
  }

  async function open(id) {
    const detail = await run(() => getPerson(id));
    if (!detail) return;
    setSelected(detail);
    const { name, email, mobile, cadre } = detail.person;
    setEdit({
      name,
      email: email ?? "",
      mobile: mobile ?? "",
      cadre: cadre ?? "",
    });
  }

  // Applies an account action and refreshes the details and list.
  async function act(prompt, work) {
    if (prompt && !window.confirm(prompt)) return;
    const person = await run(work);
    if (!person) return;
    await open(person.id);
    setPage((previous) => ({
      ...previous,
      items: previous.items.map((p) => (p.id === person.id ? person : p)),
    }));
  }

  async function add(event) {
    event.preventDefault();
    const { hasGpf, gpfNumber, username, ...details } = draft;
    const result = await run(() =>
      createPerson({
        ...details,
        gpfNumber: hasGpf ? gpfNumber : "",
        username: hasGpf ? "" : username,
      }),
    );
    if (!result) return;
    setIssued(result);
    setDraft(EMPTY);
    void search();
  }

  async function upload(event) {
    event.preventDefault();
    if (!file) return;
    const result = await run(() => bulkAddPeople(file));
    if (!result) return;
    setReport(result);
    void search();
  }

  const person = selected?.person;
  const isSelf = person?.loginId === self.username;
  const set = (field) => (value) =>
    setDraft((previous) => ({ ...previous, [field]: value }));

  return (
    <>
      {error && (
        <p className="admin-error" role="alert">
          {error}
        </p>
      )}
      {issued && (
        <section className="profile-section admin-issued" role="status">
          <header>
            <h2>Temporary password for {issued.loginId}</h2>
            <p>
              Shown only once. Give it to the person directly, never by chat. At
              first sign-in they confirm a code sent to their email and choose
              their own password.
            </p>
          </header>
          <div className="password-form">
            <code className="admin-secret">{issued.temporaryPassword}</code>
            <div className="password-actions">
              <button type="button" onClick={() => setIssued(null)}>
                Done
              </button>
            </div>
          </div>
        </section>
      )}

      <section className="profile-section">
        <header>
          <h2>People</h2>
          <p>Search by name, GPF number or username, email or mobile.</p>
        </header>
        <div className="password-form">
          <form className="password-field" role="search" onSubmit={search}>
            <label htmlFor="admin-people-search">Search</label>
            <input
              id="admin-people-search"
              type="search"
              maxLength={100}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </form>
          <ul className="admin-accounts">
            {page.items.map((p) => (
              <li key={p.id}>
                <button
                  type="button"
                  className="admin-row"
                  onClick={() => open(p.id)}
                  aria-label={`Open ${p.name} (${p.loginId})`}
                >
                  <strong>{p.name}</strong>{" "}
                  <span className="admin-username">
                    {p.gpfNumber ? `GPF ${p.gpfNumber}` : `@${p.loginId}`}
                    {p.cadre ? ` · ${p.cadre}` : ""}
                  </span>
                  <span className="admin-badges">
                    {p.administrator && <span>Administrator</span>}
                    {!p.enabled && <span>Disabled</span>}
                    {p.mustChangePassword && <span>Password not set</span>}
                  </span>
                </button>
              </li>
            ))}
          </ul>
          {page.hasMore && (
            <div className="password-actions">
              <button type="button" disabled={pending} onClick={more}>
                Show more
              </button>
            </div>
          )}
        </div>
      </section>

      {person && edit && (
        <section className="profile-section" aria-label="Person details">
          <header>
            <h2>{person.name}</h2>
            <p>
              {person.gpfNumber
                ? `GPF ${person.gpfNumber}`
                : `Username ${person.loginId}`}
              {person.lastLoginAt
                ? ` · last signed in ${new Date(person.lastLoginAt).toLocaleString()}`
                : " · never signed in"}
            </p>
          </header>
          <form
            className="password-form"
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              void act(null, () => updatePerson(person.id, edit));
            }}
          >
            {[
              ["name", "Full name", 100],
              ["email", "Email (sign-in codes go here)", 254],
              ["mobile", "Mobile", 15],
              ["cadre", "Cadre", 100],
            ].map(([field, label, max]) => (
              <TextField
                key={field}
                id={`admin-edit-${field}`}
                label={label}
                maxLength={max}
                value={edit[field]}
                disabled={pending}
                onChange={(value) =>
                  setEdit((previous) => ({ ...previous, [field]: value }))
                }
              />
            ))}
            <div className="password-actions">
              <button type="submit" disabled={pending}>
                Save details
              </button>
            </div>
          </form>
          <div className="password-form">
            <div className="password-actions">
              {!isSelf && (
                <button
                  type="button"
                  disabled={pending}
                  onClick={() =>
                    act(
                      person.enabled
                        ? `Disable ${person.name}? They are signed out everywhere and cannot sign in.`
                        : null,
                      () => setPersonEnabled(person.id, !person.enabled),
                    )
                  }
                >
                  {person.enabled ? "Disable account" : "Enable account"}
                </button>
              )}
              {!isSelf && (
                <button
                  type="button"
                  disabled={pending}
                  onClick={() =>
                    act(`Sign ${person.name} out on every device?`, () =>
                      signOutPerson(person.id),
                    )
                  }
                >
                  Sign out everywhere
                </button>
              )}
              {!isSelf && (
                <button
                  type="button"
                  disabled={pending}
                  onClick={() =>
                    act(
                      `Require ${person.name} to reset their password? Their current password stops working; they set a new one with "Forgot password", using a code sent to their email.`,
                      () => requirePasswordReset(person.id),
                    )
                  }
                >
                  Require password reset
                </button>
              )}
              <button
                type="button"
                disabled={pending}
                onClick={() =>
                  act(
                    person.administrator
                      ? `Remove administrator access from ${person.name}?`
                      : `Make ${person.name} an administrator? They will sign in on the administrator page with a code sent to ${person.email}.`,
                    () =>
                      setPersonAdministrator(person.id, !person.administrator),
                  )
                }
              >
                {person.administrator
                  ? "Remove administrator"
                  : "Make administrator"}
              </button>
            </div>
          </div>
          <ol className="admin-audit" aria-label="Recent sign-ins">
            {selected.signIns.map((event, index) => (
              <li key={`${event.occurredAt}-${index}`}>
                <time dateTime={event.occurredAt}>
                  {new Date(event.occurredAt).toLocaleString()}
                </time>{" "}
                {event.outcome.replaceAll("_", " ").toLowerCase()}
                {event.clientAddress ? ` from ${event.clientAddress}` : ""}
              </li>
            ))}
          </ol>
        </section>
      )}

      <section className="profile-section">
        <header>
          <h2>Add a person</h2>
          <p>Their GPF number is their login ID; others get a username.</p>
        </header>
        <form className="password-form" noValidate onSubmit={add}>
          <fieldset className="admin-choice">
            <legend>Login ID</legend>
            <label>
              <input
                type="radio"
                name="admin-login-type"
                checked={draft.hasGpf}
                onChange={() => set("hasGpf")(true)}
              />{" "}
              GPF number
            </label>
            <label>
              <input
                type="radio"
                name="admin-login-type"
                checked={!draft.hasGpf}
                onChange={() => set("hasGpf")(false)}
              />{" "}
              No GPF number: assign a username
            </label>
          </fieldset>
          {draft.hasGpf ? (
            <TextField
              id="admin-new-gpfNumber"
              label="GPF number"
              maxLength={32}
              autoComplete="off"
              value={draft.gpfNumber}
              disabled={pending}
              onChange={set("gpfNumber")}
            />
          ) : (
            <TextField
              id="admin-new-username"
              label="Username"
              maxLength={64}
              autoComplete="off"
              value={draft.username}
              disabled={pending}
              onChange={set("username")}
            />
          )}
          {[
            ["name", "Full name", 100],
            ["email", "Email", 254],
            ["mobile", "Mobile (optional)", 15],
            ["cadre", "Cadre (optional)", 100],
          ].map(([field, label, max]) => (
            <TextField
              key={field}
              id={`admin-new-${field}`}
              label={label}
              maxLength={max}
              autoComplete="off"
              value={draft[field]}
              disabled={pending}
              onChange={set(field)}
            />
          ))}
          <div className="password-actions">
            <button type="submit" disabled={pending}>
              Add person
            </button>
          </div>
        </form>
      </section>

      <section className="profile-section">
        <header>
          <h2>Add many people</h2>
          <p>
            A CSV file (Excel: Save as CSV UTF-8) with the columns gpf_number,
            username, name, email, mobile, cadre. No passwords: people set
            theirs with &quot;Forgot password&quot;. Up to 1000 rows.
          </p>
        </header>
        <form className="password-form" onSubmit={upload}>
          <div className="password-field">
            <label htmlFor="admin-bulk-file">CSV file</label>
            <input
              id="admin-bulk-file"
              type="file"
              accept=".csv,text/csv"
              disabled={pending}
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
            />
          </div>
          <div className="password-actions">
            <button type="submit" disabled={pending || !file}>
              Add from file
            </button>
          </div>
          {report && (
            <div role="status">
              <p>
                Added {report.imported}. Skipped {report.skipped.length}.
              </p>
              <ul className="admin-audit">
                {report.skipped.map((problem) => (
                  <li key={problem.line}>
                    Line {problem.line}: {problem.reason}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </form>
      </section>
    </>
  );
}
