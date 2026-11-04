import { useEffect, useState } from "react";
import Avatar from "./Avatar";
import { findPeople } from "../../services/chatService";
import type { UserProfile } from "../../types/chat";

// Search the directory and pick people, leaving out those already chosen.
export default function PeoplePicker({
  id,
  exclude,
  onPick,
}: {
  id: string;
  exclude: string[];
  onPick: (person: UserProfile) => void;
}) {
  const [query, setQuery] = useState("");
  const [result, setResult] = useState<{
    term: string;
    people: UserProfile[];
    error: string;
  }>({ term: "", people: [], error: "" });
  const term = query.trim();
  useEffect(() => {
    let active = true;
    if (!term) return;
    const timer = window.setTimeout(async () => {
      try {
        const people = await findPeople(term);
        if (active) setResult({ term, people, error: "" });
      } catch (error) {
        if (active)
          setResult({ term, people: [], error: (error as Error).message });
      }
    }, 200);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [term]);
  const people = result.people.filter((p) => !exclude.includes(p.id));
  return (
    <div className="people-picker">
      <label htmlFor={id}>Add people</label>
      <input
        id={id}
        placeholder="Search people"
        maxLength={100}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      {term &&
        (result.term !== term ? (
          <p role="status">Searching…</p>
        ) : result.error ? (
          <p role="alert">{result.error}</p>
        ) : !people.length ? (
          <p role="status">No people found</p>
        ) : (
          <ul>
            {people.map((person) => (
              <li key={person.id}>
                <Avatar name={person.name} />
                <span>
                  <strong>{person.name}</strong>
                  <small>@{person.username}</small>
                </span>
                <button
                  type="button"
                  aria-label={`Add ${person.name}`}
                  onClick={() => onPick(person)}
                >
                  Add
                </button>
              </li>
            ))}
          </ul>
        ))}
    </div>
  );
}
