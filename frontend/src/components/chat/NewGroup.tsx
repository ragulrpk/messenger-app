import { useState } from "react";
import PeoplePicker from "./PeoplePicker";
import BulkAdd from "./BulkAdd";
import type { UserProfile } from "../../types/chat";

// Name a new group and choose its first members.
export default function NewGroup({
  onCreate,
  onCancel,
}: {
  onCreate: (
    name: string,
    memberIds: string[],
    adminsOnly: boolean,
  ) => Promise<void>;
  onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [adminsOnly, setAdminsOnly] = useState(false);
  const [members, setMembers] = useState<UserProfile[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const ready = !!name.trim() && members.length > 0 && !busy;
  async function create() {
    if (!ready) return;
    setBusy(true);
    setError("");
    try {
      await onCreate(
        name.trim(),
        members.map((m) => m.id),
        adminsOnly,
      );
    } catch (failure) {
      setError((failure as Error).message);
      setBusy(false);
    }
  }
  return (
    <form
      className="group-panel"
      aria-label="New group"
      onSubmit={(e) => {
        e.preventDefault();
        void create();
      }}
    >
      <strong>New group</strong>
      <label htmlFor="group-name">Group name</label>
      <input
        id="group-name"
        maxLength={100}
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
      <label className="group-option">
        <input
          type="checkbox"
          checked={adminsOnly}
          onChange={(e) => setAdminsOnly(e.target.checked)}
        />
        Announcement group: only admins can send messages
      </label>
      {members.length > 0 && (
        <ul className="group-chips" aria-label="Chosen people">
          {members.map((m) => (
            <li key={m.id}>
              {m.name}
              <button
                type="button"
                aria-label={`Remove ${m.name}`}
                onClick={() =>
                  setMembers((previous) =>
                    previous.filter((p) => p.id !== m.id),
                  )
                }
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
      <PeoplePicker
        id="group-people"
        exclude={members.map((m) => m.id)}
        onPick={(person) => setMembers((previous) => [...previous, person])}
      />
      <BulkAdd
        id="group-bulk"
        exclude={members.map((m) => m.id)}
        limit={99 - members.length}
        onAdd={(people) => setMembers((previous) => [...previous, ...people])}
      />
      {error && <p role="alert">{error}</p>}
      <div className="group-panel-actions">
        <button type="submit" disabled={!ready}>
          {busy ? "Creating…" : "Create group"}
        </button>
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
