import { useState } from "react";
import { lookupPeople } from "../../services/chatService";
import type { UserProfile } from "../../types/chat";

// Paste a list of usernames, look them all up at once, and add the ones found.
export default function BulkAdd({
  id,
  exclude,
  limit,
  onAdd,
}: {
  id: string;
  exclude: string[];
  // How many more people may be added.
  limit: number;
  onAdd: (people: UserProfile[]) => Promise<void> | void;
}) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  // Usernames separated by commas, semicolons, spaces or new lines; a leading @ is allowed.
  const names = [
    ...new Set(
      text
        .split(/[\s,;]+/)
        .map((n) => n.replace(/^@/, "").trim().toLowerCase())
        .filter(Boolean),
    ),
  ];
  async function add() {
    if (!names.length || busy) return;
    setBusy(true);
    setMessage("");
    try {
      const found = (await lookupPeople(names)).filter(
        (p) => !exclude.includes(p.id),
      );
      const foundNames = new Set(
        found.map((p) => (p.username ?? "").toLowerCase()),
      );
      const missing = names.filter((n) => !foundNames.has(n));
      const added = found.slice(0, limit);
      if (added.length) await onAdd(added);
      const parts = [
        `Added ${added.length} ${added.length === 1 ? "person" : "people"}.`,
      ];
      if (found.length > added.length)
        parts.push(
          `${found.length - added.length} not added: the group is full.`,
        );
      if (missing.length)
        parts.push(
          `Not found or already added: ${missing.slice(0, 20).join(", ")}${missing.length > 20 ? ` and ${missing.length - 20} more` : ""}.`,
        );
      setMessage(parts.join(" "));
      if (!missing.length) setText("");
    } catch (failure) {
      setMessage((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }
  if (!open)
    return (
      <button
        type="button"
        className="bulk-add-open"
        onClick={() => setOpen(true)}
      >
        Paste usernames to add many people
      </button>
    );
  return (
    <div className="bulk-add">
      <label htmlFor={id}>Paste usernames</label>
      <textarea
        id={id}
        rows={4}
        placeholder="ravi, priya, alex — or one per line"
        value={text}
        onChange={(e) => setText(e.target.value)}
      />
      <div className="bulk-add-actions">
        <button
          type="button"
          disabled={!names.length || busy || limit < 1}
          onClick={() => void add()}
        >
          {busy
            ? "Adding…"
            : `Add ${names.length || ""} ${names.length === 1 ? "person" : "people"}`}
        </button>
        <button type="button" onClick={() => setOpen(false)}>
          Close
        </button>
      </div>
      {message && (
        <p className="bulk-add-result" role="status">
          {message}
        </p>
      )}
    </div>
  );
}
