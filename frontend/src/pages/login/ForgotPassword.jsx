import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  completePasswordReset,
  startPasswordReset,
} from "../../services/authService.js";
import { Field, SignInFrame } from "./Login";
import { validateNewPassword } from "./useLogin";
import "./Login.css";

// Forgot password: the person enters their login ID and chooses a new
// password, which signs out every other session.
export default function ForgotPassword() {
  const [loginId, setLoginId] = useState("");
  const [started, setStarted] = useState(false);
  const [values, setValues] = useState({
    newPassword: "",
    confirmPassword: "",
  });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [done, setDone] = useState(false);
  const [loading, setLoading] = useState(false);
  const busy = useRef(false);

  async function run(work) {
    if (busy.current) return;
    busy.current = true;
    setLoading(true);
    setError("");
    try {
      await work();
    } catch (failure) {
      if (failure.fieldErrors) setErrors(failure.fieldErrors);
      else setError(failure.message || "Something went wrong. Try again.");
      if (failure.code === "NO_PENDING_SIGN_IN") setStarted(false);
    } finally {
      busy.current = false;
      setLoading(false);
    }
  }

  function change(event) {
    const { name, value } = event.target;
    if (name === "loginId") setLoginId(value);
    else setValues((previous) => ({ ...previous, [name]: value }));
    setErrors((previous) => ({ ...previous, [name]: undefined }));
    setError("");
  }

  async function start(event) {
    event.preventDefault();
    if (!loginId.trim()) {
      setErrors({ loginId: "Enter your GPF number or username." });
      return;
    }
    await run(async () => {
      await startPasswordReset(loginId.trim());
      setStarted(true);
    });
  }

  async function complete(event) {
    event.preventDefault();
    const validation = validateNewPassword(values);
    setErrors(validation);
    if (Object.keys(validation).length) return;
    await run(async () => {
      await completePasswordReset(values);
      setValues({ newPassword: "", confirmPassword: "" });
      setDone(true);
    });
  }

  return (
    <SignInFrame
      title="Reset your password"
      subtitle="Enter your GPF number or username, then choose a new password."
    >
      {done ? (
        <div className="signin-form">
          <p className="signin-notice" role="status">
            Your password was changed and you were signed out everywhere. Sign
            in with your new password.
          </p>
          <Link className="signin-submit" to="/login">
            Sign in <span aria-hidden="true">→</span>
          </Link>
        </div>
      ) : !started ? (
        <form
          className="signin-form"
          noValidate
          onSubmit={start}
          aria-busy={loading}
        >
          <Field
            id="loginId"
            label="GPF number or username"
            autoComplete="username"
            value={loginId}
            onChange={change}
            disabled={loading}
            error={errors.loginId}
          />
          {error && (
            <div className="signin-error" role="alert">
              {error}
            </div>
          )}
          <button className="signin-submit" type="submit" disabled={loading}>
            {loading ? "Checking…" : "Continue"}
            <span aria-hidden="true">→</span>
          </button>
        </form>
      ) : (
        <form
          className="signin-form"
          noValidate
          onSubmit={complete}
          aria-busy={loading}
        >
          <Field
            id="newPassword"
            label="New password (at least 12 characters)"
            type="password"
            autoComplete="new-password"
            maxLength={72}
            value={values.newPassword}
            onChange={change}
            disabled={loading}
            error={errors.newPassword}
          />
          <Field
            id="confirmPassword"
            label="Confirm new password"
            type="password"
            autoComplete="new-password"
            maxLength={72}
            value={values.confirmPassword}
            onChange={change}
            disabled={loading}
            error={errors.confirmPassword}
          />
          {error && (
            <div className="signin-error" role="alert">
              {error}
            </div>
          )}
          <button className="signin-submit" type="submit" disabled={loading}>
            {loading ? "Saving…" : "Set new password"}
            <span aria-hidden="true">→</span>
          </button>
        </form>
      )}
      <nav className="signin-links" aria-label="Other sign-in options">
        <Link to="/login">Back to sign-in</Link>
      </nav>
    </SignInFrame>
  );
}
