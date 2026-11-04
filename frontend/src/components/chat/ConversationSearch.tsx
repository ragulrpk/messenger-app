import { useEffect, useState } from "react";
import Avatar from "./Avatar";
import { findPeople, findGroups } from "../../services/chatService";
import type { ConversationType, UserProfile } from "../../types/chat";

// Search the people directory and the user's own groups, and display selectable results.
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
    groups: Awaited<ReturnType<typeof findGroups>>;
    error: string;
  }>({ term: "", people: [], groups: [], error: "" });
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    if (!term) return;
    const timer = window.setTimeout(async () => {
      try {
        const [people, groups] = await Promise.all([
          findPeople(term),
          findGroups(term),
        ]);
        if (active) setResult({ term, people, groups, error: "" });
      } catch (error) {
        if (active)
          setResult({
            term,
            people: [],
            groups: [],
            error: (error as Error).message,
          });
      }
    }, 200);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [term, retry]);
  const matchingGroups = result.term === term ? result.groups : [];
  const loading = !!term && result.term !== term;
  return (
    <section className="conversation-search" aria-label="Search directory">
      <div className="search-field">
        <label className="sr-only" htmlFor="people-search">
          Search people and groups
        </label>
        <span aria-hidden="true">⌕</span>
        <input
          id="people-search"
          placeholder="Search people and groups"
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
          {matchingGroups.length > 0 && (
            <>
              <h3>Groups</h3>
              {matchingGroups.map((group) => (
                <button
                  className="search-result"
                  key={group.id}
                  onClick={() => onSelect("GROUP", group.id, group.name)}
                >
                  <Avatar name={group.name} group />
                  <span>
                    <strong>{group.name}</strong>
                    <small>{group.memberCount} members</small>
                  </span>
                </button>
              ))}
              {matchingGroups.length === 50 && (
                <p>Showing the first 50 groups. Refine your search for more.</p>
              )}
            </>
          )}
          {loading ? (
            <p role="status">Searching…</p>
          ) : result.error ? (
            <p role="alert">
              {result.error}{" "}
              <button
                onClick={() => {
                  setResult({ term: "", people: [], groups: [], error: "" });
                  setRetry((v) => v + 1);
                }}
              >
                Retry search
              </button>
            </p>
          ) : !result.people.length ? (
            !matchingGroups.length && (
              <p role="status">No people or groups found</p>
            )
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
