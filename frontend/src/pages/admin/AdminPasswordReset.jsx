import { useEffect, useRef, useState } from "react";
import { Link, Navigate } from "react-router-dom";
import { useAuth } from "../../context/useAuth";
import { adminResetPassword } from "../../services/authService";
import { validatePasswordChange } from "../profile/passwordValidation";
import Brand from "../../components/Brand";
import "../profile/Profile.css";

const empty = {
  username: "",
  currentPassword: "",
  newPassword: "",
  confirmPassword: "",
};

// 2026-09-23: Administrator-assisted recovery. Credentials stay in memory and are cleared after success.
export default function AdminPasswordReset() {
  const { user } = useAuth();
  const [values, setValues] = useState(empty);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [verified, setVerified] = useState(false);
  const [visible, setVisible] = useState(false);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const heading = useRef(null);
  useEffect(() => {
    heading.current?.focus();
  }, []);

  if (user?.administrator !== true) return <Navigate to="/chat" replace />;

  async function submit(event) {
    event.preventDefault();
    if (busy.current) return;
    const validation = validatePasswordChange(values);
    if (!values.username.trim() || values.username.trim().length > 64)
      validation.username = "Enter a username of 64 characters or fewer.";
    else if (values.username.trim().toLowerCase() === user.username)
      validation.username = "Use Change password to update your own account.";
    if (!verified)
      validation.verified =
        "Verify the user’s identity before resetting their password.";
    setErrors(validation);
    setError("");
    setNotice("");
    if (Object.keys(validation).length) return;
    busy.current = true;
    setPending(true);
    try {
      await adminResetPassword(values);
      setNotice(
        `Password reset for ${values.username.trim()}. Their previous sessions are now invalid. Share the new password securely and ask them to change it after signing in.`,
      );
      setValues(empty);
      setVerified(false);
      setVisible(false);
    } catch (failure) {
      if (failure.fieldErrors) setErrors(failure.fieldErrors);
      else
        setError(
          `${failure.message} If the connection was interrupted, check whether the new password works before retrying.`,
        );
      if (failure.status === 401)
        window.dispatchEvent(new Event("messenger:session-expired"));
    } finally {
      busy.current = false;
      setPending(false);
    }
  }

  return (
    <div className="profile-page">
      <header className="profile-topbar">
        <Brand />
        <Link to="/chat">← Back to chat</Link>
      </header>
      <main className="profile-content" aria-labelledby="admin-reset-title">
        <div className="profile-heading">
          <span>ADMINISTRATION</span>
          <h1 id="admin-reset-title" ref={heading} tabIndex={-1}>
            Reset a user’s password
          </h1>
          <p>
            Verify the person’s identity before giving them access to an
            account.
          </p>
        </div>
        <section className="profile-section">
          <form
            className="password-form"
            onSubmit={submit}
            noValidate
            aria-busy={pending}
          >
            <p id="admin-reset-help">
              Enter the user’s username and a new password of at least 12
              characters, up to 72 UTF-8 bytes. Confirm with your own
              administrator password. The user’s existing sessions will end.
            </p>
            {Object.entries({
              username: "User’s username",
              currentPassword: "Your administrator password",
              newPassword: "New password for the user",
              confirmPassword: "Confirm new password",
            }).map(([field, label]) => (
              <div className="password-field" key={field}>
                <label htmlFor={`admin-${field}`}>{label}</label>
                <input
                  id={`admin-${field}`}
                  name={field}
                  type={field === "username" || visible ? "text" : "password"}
                  autoComplete={
                    field === "username"
                      ? "off"
                      : field === "currentPassword"
                        ? "current-password"
                        : "new-password"
                  }
                  maxLength={field === "username" ? 64 : 72}
                  disabled={pending}
                  value={values[field]}
                  aria-invalid={Boolean(errors[field])}
                  aria-describedby={
                    errors[field] ? `admin-${field}-error` : "admin-reset-help"
                  }
                  onChange={(event) => {
                    setValues((previous) => ({
                      ...previous,
                      [field]: event.target.value,
                    }));
                    setErrors((previous) => ({
                      ...previous,
                      [field]: undefined,
                    }));
                    setNotice("");
                  }}
                />
                {errors[field] && (
                  <p
                    id={`admin-${field}-error`}
                    className="password-error"
                    role="alert"
                  >
                    {errors[field]}
                  </p>
                )}
              </div>
            ))}
            <label className="password-visibility">
              <input
                type="checkbox"
                checked={visible}
                disabled={pending}
                onChange={(event) => setVisible(event.target.checked)}
              />
              Show passwords
            </label>
            <label className="password-visibility">
              <input
                id="admin-verified"
                type="checkbox"
                checked={verified}
                disabled={pending}
                aria-describedby={
                  errors.verified ? "admin-verified-error" : undefined
                }
                onChange={(event) => setVerified(event.target.checked)}
              />
              I have verified this user’s identity.
            </label>
            {errors.verified && (
              <p
                id="admin-verified-error"
                className="password-error"
                role="alert"
              >
                {errors.verified}
              </p>
            )}
            {error && (
              <p className="password-error" role="alert">
                {error}
              </p>
            )}
            {notice && <p role="status">{notice}</p>}
            <div className="password-actions">
              <button type="submit" disabled={pending}>
                {pending ? "Resetting password…" : "Reset password"}
              </button>
              <button
                type="button"
                disabled={pending}
                onClick={() => {
                  setValues(empty);
                  setErrors({});
                  setError("");
                  setNotice("");
                  setVisible(false);
                  setVerified(false);
                }}
              >
                Clear
              </button>
            </div>
          </form>
        </section>
      </main>
    </div>
  );
}
