import { useEffect, useRef } from "react";
import { Link, useLocation } from "react-router-dom";
import Brand from "../../components/Brand";
import Avatar from "../../components/chat/Avatar";
import ChangePassword from "./ChangePassword";
import { useAuth } from "../../context/useAuth";
import type { UserProfile } from "../../types/chat";
import "./Profile.css";

// Format a valid profile date or show a missing-value label.
function profileDate(value?: string) {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return "Not provided";
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value)
    return "Not provided";
  return date.toLocaleDateString("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    timeZone: "UTC",
  });
}

// Display the signed-in user’s profile details.
export default function Profile() {
  const { user } = useAuth() as { user: UserProfile };
  const heading = useRef<HTMLHeadingElement>(null);
  const { hash } = useLocation();
  useEffect(() => {
    if (hash === "#password") {
      document
        .getElementById("change-currentPassword")
        ?.focus({ preventScroll: true });
      document.getElementById("password")?.scrollIntoView?.({ block: "start" });
    } else {
      heading.current?.focus();
    }
  }, [hash]);
  const sections = [
    {
      title: "Personal details",
      subtitle: "Your basic information",
      fields: [
        ["Name", user.name],
        ["Date of birth", profileDate(user.dateOfBirth)],
        ["Date of joining", profileDate(user.dateOfJoining)],
      ],
    },
    {
      title: "Contact details",
      subtitle: "Your contact information",
      fields: [
        ["Phone number", user.phoneNumber],
        ["Email", user.email],
      ],
    },
    {
      title: "Organisation",
      subtitle: "Your role and office",
      fields: [
        ["Designation", user.designation],
        ["Circle", user.circle],
        ["Zone", user.zone],
        ["Division", user.division],
      ],
    },
  ];
  return (
    <div className="profile-page">
      <header className="profile-topbar">
        <Brand />
        <Link to="/chat">← Back to chat</Link>
      </header>
      <main className="profile-content" aria-labelledby="profile-title">
        <div className="profile-heading">
          <span>MY ACCOUNT</span>
          <h1 id="profile-title" ref={heading} tabIndex={-1}>
            Your profile
          </h1>
          <p>
            Your personal details, contact information, and organisation in one
            place.
          </p>
        </div>
        <section className="profile-details" aria-label="Profile details">
          <aside className="profile-identity">
            <span className="profile-card-label">MEMBER PROFILE</span>
            <Avatar name={user.name} />
            <div>
              <h2>{user.name}</h2>
              {user.username && <p>@{user.username}</p>}
              <p className="profile-role">
                {user.designation?.trim() || "Designation not provided"}
              </p>
            </div>
            <div className="profile-identity-footer">
              <span>JOINED</span>
              <p>{profileDate(user.dateOfJoining)}</p>
            </div>
          </aside>
          <div className="profile-sections">
            {sections.map((section) => (
              <section
                className="profile-section"
                key={section.title}
                aria-label={section.title}
              >
                <header>
                  <h2>{section.title}</h2>
                  <p>{section.subtitle}</p>
                </header>
                <dl>
                  {section.fields.map(([label, value]) => (
                    <div key={label}>
                      <dt>{label}</dt>
                      <dd
                        className={
                          value?.trim() && value !== "Not provided"
                            ? undefined
                            : "profile-missing"
                        }
                      >
                        {value?.trim() || "Not provided"}
                      </dd>
                    </div>
                  ))}
                </dl>
              </section>
            ))}
            <ChangePassword />
          </div>
        </section>
      </main>
    </div>
  );
}
