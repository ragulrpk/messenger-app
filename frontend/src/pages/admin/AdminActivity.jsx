import { useEffect, useState } from "react";
import { getAuditLog, getSignIns } from "../../services/adminService";

const ACTIONS = {
  ACCOUNT_CREATED: "added",
  ACCOUNTS_IMPORTED: "imported accounts",
  ACCOUNT_UPDATED: "edited the details of",
  ACCOUNT_DISABLED: "disabled",
  ACCOUNT_ENABLED: "enabled",
  SIGNED_OUT: "signed out",
  PASSWORD_RESET_REQUIRED: "required a password reset for",
  ADMINISTRATOR_GRANTED: "made an administrator:",
  ADMINISTRATOR_REVOKED: "removed administrator from",
  GROUP_CREATED: "created the group",
  GROUP_MEMBERS_ADDED: "added members to",
  GROUP_MEMBER_REMOVED: "removed from a group:",
  GROUP_ADMIN_GRANTED: "made a group admin:",
  GROUP_ADMIN_REVOKED: "removed group admin from",
  GROUP_RENAMED: "renamed a group:",
  GROUP_ANNOUNCEMENTS_ON: "made announcements-only:",
  GROUP_ANNOUNCEMENTS_OFF: "let everyone post in",
  GROUP_ARCHIVED: "archived",
  GROUP_RESTORED: "restored",
};

// Who did what, and recent sign-ins (successes, failures, codes sent).
export default function AdminActivity() {
  const [audit, setAudit] = useState([]);
  const [signIns, setSignIns] = useState([]);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    Promise.all([getAuditLog(), getSignIns()]).then(
      ([entries, events]) => {
        if (!active) return;
        setAudit(entries);
        setSignIns(events);
      },
      (failure) => active && setError(failure.message),
    );
    return () => {
      active = false;
    };
  }, []);

  return (
    <>
      {error && (
        <p className="admin-error" role="alert">
          {error}
        </p>
      )}
      <section className="profile-section">
        <header>
          <h2>Administrative changes</h2>
          <p>The latest 200, newest first.</p>
        </header>
        <ol className="admin-audit">
          {audit.map((entry, index) => (
            <li key={`${entry.occurredAt}-${index}`}>
              <time dateTime={entry.occurredAt}>
                {new Date(entry.occurredAt).toLocaleString()}
              </time>{" "}
              <strong>{entry.actor ?? "Server import"}</strong>{" "}
              {ACTIONS[entry.action] ?? entry.action}
              {entry.target && (
                <>
                  {" "}
                  <strong>{entry.target}</strong>
                </>
              )}
              {entry.detail && ` ${entry.detail}`}
            </li>
          ))}
        </ol>
      </section>
      <section className="profile-section">
        <header>
          <h2>Sign-ins</h2>
          <p>The latest 200 attempts, including failures and codes sent.</p>
        </header>
        <ol className="admin-audit">
          {signIns.map((event, index) => (
            <li key={`${event.occurredAt}-${index}`}>
              <time dateTime={event.occurredAt}>
                {new Date(event.occurredAt).toLocaleString()}
              </time>{" "}
              <strong>{event.loginId}</strong>{" "}
              {event.outcome.replaceAll("_", " ").toLowerCase()}
              {event.clientAddress ? ` from ${event.clientAddress}` : ""}
            </li>
          ))}
        </ol>
      </section>
    </>
  );
}
