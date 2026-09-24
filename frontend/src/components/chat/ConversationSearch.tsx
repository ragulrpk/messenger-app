import { useEffect, useState } from "react";
import Avatar from "./Avatar";
import { findPeople } from "../../services/chatService";
import type { ConversationType, UserProfile } from "../../types/chat";

// Search the people directory and display selectable results.
export default function ConversationSearch({
  query,
  onQuery,
  onSelect,
}: {
  query: string;
  onQuery: (value: string) => void;
  onSelect: (type: ConversationType, id: string, name: string) => void;
}) {
  const term = query.trim();
  const [result, setResult] = useState<{
    term: string;
    people: UserProfile[];
    error: string;
  }>({ term: "", people: [], error: "" });
  const [retry, setRetry] = useState(0);
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
  }, [term, retry]);
  const loading = !!term && result.term !== term;
  return (
    <section className="conversation-search" aria-label="Search directory">
      <div className="search-field">
        <label className="sr-only" htmlFor="people-search">
          Search people
        </label>
        <span aria-hidden="true">⌕</span>
        <input
          id="people-search"
          placeholder="Search people"
          maxLength={100}
          value={query}
          onChange={(e) => onQuery(e.target.value)}
        />
        {query && (
          <button
            className="icon-button"
            aria-label="Clear search"
            onClick={() => onQuery("")}
          >
            ×
          </button>
        )}
      </div>
      {term && (
        <div className="search-results" aria-busy={loading}>
          {loading ? (
            <p role="status">Searching…</p>
          ) : result.error ? (
            <p role="alert">
              {result.error}{" "}
              <button
                onClick={() => {
                  setResult({ term: "", people: [], error: "" });
                  setRetry((v) => v + 1);
                }}
              >
                Retry search
              </button>
            </p>
          ) : !result.people.length ? (
            <p role="status">No people found</p>
          ) : (
            <>
              <h3>People</h3>
              {result.people.map((person) => (
                <button
                  className="search-result"
                  key={person.id}
                  onClick={() => onSelect("DIRECT", person.id, person.name)}
                >
                  <Avatar name={person.name} />
                  <span>
                    <strong>{person.name}</strong>
                    <small>@{person.username}</small>
                  </span>
                </button>
              ))}
              {result.people.length === 50 && (
                <p>
                  Showing the first 50 matches. Refine your search for more.
                </p>
              )}
            </>
          )}
        </div>
      )}
    </section>
  );
}
