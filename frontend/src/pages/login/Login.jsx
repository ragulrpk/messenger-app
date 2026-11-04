import { Link } from "react-router-dom";
import useLogin from "./useLogin";
import { useAuth } from "../../context/useAuth";
import "./Login.css";
import Brand from "../../components/Brand";

//Latest chagnes - 2
// A labelled input with its error, styled like the rest of the sign-in form.
export function Field({
  id,
  label,
  error,
  type = "text",
  autoComplete,
  ...input
}) {
  return (
    <div className="signin-field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        name={id}
        type={type}
        autoComplete={autoComplete}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${id}-error` : undefined}
        {...input}
      />
      {error && (
        <p className="signin-field-error" id={`${id}-error`} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

// The page frame shared by sign-in, administrator sign-in and forgot password.
export function SignInFrame({ title, subtitle, children }) {
  return (
    <main className="signin-screen">
      <div className="signin-art" aria-hidden="true">
        <div className="signin-orbit signin-orbit-outer" />
        <div className="signin-orbit signin-orbit-inner" />
        <div className="signin-spark signin-spark-one">✦</div>
        <div className="signin-spark signin-spark-two">✧</div>
        <div className="signin-note signin-note-left">
          <span className="signin-note-icon">↗</span>
          <div>
            <strong>A little hello.</strong>
            <span>A new possibility.</span>
          </div>
          <div className="signin-note-lines">
            <i />
            <i />
          </div>
        </div>
        <div className="signin-note signin-note-right">
          <div className="signin-dots">
            <i />
            <i />
            <i />
          </div>
          <strong>Good things start here.</strong>
          <span>Keep the conversation going.</span>
        </div>
        <span className="signin-art-caption">PEOPLE. IDEAS. CONNECTIONS.</span>
      </div>
      <section className="signin-panel" aria-labelledby="signin-title">
        <header className="signin-heading">
          <Brand />
          <h1 id="signin-title">{title}</h1>
          <p>{subtitle}</p>
        </header>
        {children}
      </section>
    </main>
  );
}

// Employee or administrator sign-in: password, then a new password when the
// current one is temporary.
export default function Login({ admin = false }) {
  const { sessionNotice } = useAuth();
  const form = useLogin(admin);
  const { errors, loading, challenge } = form;

  return (
    <SignInFrame
      title={admin ? "Administrator sign-in" : "Hello again!"}
      subtitle={
        admin
          ? "Sign in with your administrator username and password."
          : "Your people are just a hello away."
      }
    >
      {sessionNotice && !challenge && (
        <p className="signin-notice" role="status">
          {sessionNotice}
        </p>
      )}
      {!challenge ? (
        <form
          className="signin-form"
          noValidate
          onSubmit={form.handleSubmit}
          aria-busy={loading}
        >
          <Field
            id="username"
            label={admin ? "Username" : "GPF number or username"}
            autoComplete="username"
            placeholder={
              admin ? "Enter your username" : "Enter your GPF number"
            }
            value={form.username}
            onChange={form.handleChange}
            disabled={loading}
            error={errors.username}
          />
          <div className="signin-field">
            <label htmlFor="password">Password</label>
            <div className="signin-password">
              <input
                id="password"
                name="password"
                type={form.showPassword ? "text" : "password"}
                autoComplete="current-password"
                placeholder="Enter your password"
                value={form.password}
                onChange={form.handleChange}
                disabled={loading}
                aria-invalid={Boolean(errors.password)}
                aria-describedby={
                  errors.password ? "password-error" : undefined
                }
              />
              <button
                type="button"
                onClick={form.togglePassword}
                aria-label={
                  form.showPassword ? "Hide password" : "Show password"
                }
              >
                {form.showPassword ? "Hide" : "Show"}
              </button>
            </div>
            {errors.password && (
              <p
                className="signin-field-error"
                id="password-error"
                role="alert"
              >
                {errors.password}
              </p>
            )}
          </div>
          {form.error && (
            <div className="signin-error" role="alert">
              {form.error}
            </div>
          )}
          <button className="signin-submit" type="submit" disabled={loading}>
            {loading ? "Signing in…" : "Sign in"}
            <span aria-hidden="true">→</span>
          </button>
        </form>
      ) : (
        <form
          className="signin-form"
          noValidate
          onSubmit={form.handleVerify}
          aria-busy={loading}
        >
          <p className="signin-notice" role="status">
            Your password is temporary. Choose your own password to continue.
          </p>
          <Field
            id="newPassword"
            label="New password (at least 12 characters)"
            type="password"
            autoComplete="new-password"
            maxLength={72}
            value={form.passwords.newPassword}
            onChange={form.handleChange}
            disabled={loading}
            error={errors.newPassword}
          />
          <Field
            id="confirmPassword"
            label="Confirm new password"
            type="password"
            autoComplete="new-password"
            maxLength={72}
            value={form.passwords.confirmPassword}
            onChange={form.handleChange}
            disabled={loading}
            error={errors.confirmPassword}
          />
          {form.notice && <p role="status">{form.notice}</p>}
          {form.error && (
            <div className="signin-error" role="alert">
              {form.error}
            </div>
          )}
          <button className="signin-submit" type="submit" disabled={loading}>
            {loading ? "Saving…" : "Continue"}
            <span aria-hidden="true">→</span>
          </button>
          <div className="signin-links">
            <button type="button" onClick={form.startOver} disabled={loading}>
              Start over
            </button>
          </div>
        </form>
      )}
      <nav className="signin-links" aria-label="Other sign-in options">
        <Link to="/forgot-password">Forgot password?</Link>
        {admin ? (
          <Link to="/login">Employee sign-in</Link>
        ) : (
          <Link to="/admin/login">Administrator sign-in</Link>
        )}
      </nav>
      <footer className="signin-footer">
        Need an account? Contact your administrator.
      </footer>
    </SignInFrame>
  );
}
