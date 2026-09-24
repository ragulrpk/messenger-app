import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../../context/useAuth";
import { validatePasswordChange } from "./passwordValidation";

const empty = { currentPassword: "", newPassword: "", confirmPassword: "" };

export default function ChangePassword() {
  const { updatePassword } = useAuth();
  const navigate = useNavigate();
  const [values, setValues] = useState(empty);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [visible, setVisible] = useState(false);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  async function submit(event) {
    event.preventDefault();
    if (busy.current) return;
    const validation = validatePasswordChange(values);
    setErrors(validation);
    setError("");
    if (Object.keys(validation).length) return;
    busy.current = true;
    setPending(true);
    try {
      await updatePassword(values);
      if (mounted.current) {
        setValues(empty);
        // No password is placed in navigation state, the URL, or browser storage.
        navigate("/login", { replace: true, state: { passwordChanged: true } });
      }
    } catch (failure) {
      if (mounted.current) {
        if (failure.fieldErrors) setErrors(failure.fieldErrors);
        else
          setError(
            `${failure.message} If the connection was interrupted, the change may have completed; try signing in with your new password.`,
          );
      }
    } finally {
      busy.current = false;
      if (mounted.current) setPending(false);
    }
  }

  return (
    <section
      id="password"
      className="profile-section password-section"
      aria-labelledby="password-title"
    >
      <header>
        <h2 id="password-title">Change password</h2>
        <p>Choose a new password for your account.</p>
      </header>
      <form
        className="password-form"
        onSubmit={submit}
        noValidate
        aria-busy={pending}
      >
        <p id="password-help">
          Use at least 12 characters, up to 72 UTF-8 bytes. Changing your
          password signs you out on all devices.
        </p>
        {Object.entries({
          currentPassword: "Current password",
          newPassword: "New password",
          confirmPassword: "Confirm new password",
        }).map(([field, label]) => (
          <div className="password-field" key={field}>
            <label htmlFor={`change-${field}`}>{label}</label>
            <input
              id={`change-${field}`}
              name={field}
              type={visible ? "text" : "password"}
              autoComplete={
                field === "currentPassword"
                  ? "current-password"
                  : "new-password"
              }
              value={values[field]}
              disabled={pending}
              maxLength={72}
              aria-invalid={Boolean(errors[field])}
              aria-describedby={
                errors[field] ? `change-${field}-error` : "password-help"
              }
              onChange={(event) => {
                setValues((previous) => ({
                  ...previous,
                  [field]: event.target.value,
                }));
                setErrors((previous) => ({ ...previous, [field]: undefined }));
                setError("");
              }}
            />
            {errors[field] && (
              <p
                className="password-error"
                id={`change-${field}-error`}
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
        {error && (
          <p className="password-error" role="alert">
            {error}
          </p>
        )}
        <div className="password-actions">
          <button type="submit" disabled={pending}>
            {pending ? "Changing password…" : "Change password"}
          </button>
          <button
            type="button"
            disabled={pending}
            onClick={() => {
              setValues(empty);
              setErrors({});
              setError("");
              setVisible(false);
            }}
          >
            Clear
          </button>
        </div>
      </form>
    </section>
  );
}
