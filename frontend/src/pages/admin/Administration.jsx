import { useEffect, useRef, useState } from "react";
import { Link, Navigate } from "react-router-dom";
import { useAuth } from "../../context/useAuth";
import Brand from "../../components/Brand";
import AdminPeople from "./AdminPeople";
import AdminGroups from "./AdminGroups";
import AdminActivity from "./AdminActivity";
import "../profile/Profile.css";

const TABS = [
  ["people", "People"],
  ["groups", "Groups"],
  ["activity", "Activity"],
];

// Workspace administration, like Slack or Teams: people, groups and an
// activity record. The server checks for an administrator who signed in with
// the emailed code on every request; this page only follows along.
export default function Administration() {
  const { user } = useAuth();
  const [tab, setTab] = useState("people");
  const heading = useRef(null);
  useEffect(() => heading.current?.focus(), []);
  if (user?.administrator !== true) return <Navigate to="/chat" replace />;

  return (
    <div className="profile-page">
      <header className="profile-topbar">
        <Brand />
        <Link to="/chat">← Back to chat</Link>
      </header>
      <main className="profile-content" aria-labelledby="admin-title">
        <div className="profile-heading">
          <span>ADMINISTRATION</span>
          <h1 id="admin-title" ref={heading} tabIndex={-1}>
            Workspace
          </h1>
          <p>
            Manage people and groups. Every change is recorded and visible to
            all administrators.
          </p>
        </div>
        <div className="admin-tabs" role="tablist" aria-label="Administration">
          {TABS.map(([id, label]) => (
            <button
              key={id}
              id={`admin-tab-${id}`}
              type="button"
              role="tab"
              aria-selected={tab === id}
              aria-controls={`admin-panel-${id}`}
              onClick={() => setTab(id)}
            >
              {label}
            </button>
          ))}
        </div>
        <div
          id={`admin-panel-${tab}`}
          role="tabpanel"
          aria-labelledby={`admin-tab-${tab}`}
          className="profile-sections"
        >
          {tab === "people" && <AdminPeople self={user} />}
          {tab === "groups" && <AdminGroups />}
          {tab === "activity" && <AdminActivity />}
        </div>
      </main>
    </div>
  );
}
