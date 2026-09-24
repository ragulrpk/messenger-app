import useLogin from "./useLogin";
import { useAuth } from "../../context/useAuth";
import "./Login.css";

// Render the sign-in form and its validation feedback.
export default function Login() {
  const { sessionNotice } = useAuth();
  const {
    username,
    password,
    showPassword,
    errors,
    error,
    loading,
    handleChange,
    handleSubmit,
    togglePassword,
  } = useLogin();

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
          <svg
            className="signin-symbol"
            viewBox="0 0 40 40"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M8 9h24v17H19l-8 7v-7H8V9Z"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinejoin="round"
            />
            <path
              d="M15 16h10M15 21h6"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
          <span className="signin-brand">MESSENGER</span>
          <h1 id="signin-title">Hello again!</h1>
          <p>Your people are just a hello away.</p>
        </header>

        {sessionNotice && <p role="status">{sessionNotice}</p>}
        <form
          className="signin-form"
          noValidate
          onSubmit={handleSubmit}
          aria-busy={loading}
        >
          <div className="signin-field">
            <label htmlFor="username">Username</label>
            <input
              id="username"
              name="username"
              type="text"
              autoComplete="username"
              placeholder="Enter your username"
              value={username}
              onChange={handleChange}
              disabled={loading}
              aria-invalid={Boolean(errors.username)}
              aria-describedby={errors.username ? "username-error" : undefined}
            />
            {errors.username && (
              <p
                className="signin-field-error"
                id="username-error"
                role="alert"
              >
                {errors.username}
              </p>
            )}
          </div>

          <div className="signin-field">
            <label htmlFor="password">Password</label>
            <div className="signin-password">
              <input
                id="password"
                name="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                placeholder="Enter your password"
                value={password}
                onChange={handleChange}
                disabled={loading}
                aria-invalid={Boolean(errors.password)}
                aria-describedby={
                  errors.password ? "password-error" : undefined
                }
              />
              <button
                type="button"
                onClick={togglePassword}
                aria-label={showPassword ? "Hide password" : "Show password"}
              >
                {showPassword ? "Hide" : "Show"}
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

          {error && (
            <div className="signin-error" role="alert">
              {error}
            </div>
          )}
          <button className="signin-submit" type="submit" disabled={loading}>
            {loading ? "Signing in…" : "Sign in"}
            <span aria-hidden="true">→</span>
          </button>
        </form>
        {/* 2026-09-23: Recovery is handled by an administrator after identity verification. */}
        <details className="signin-recovery">
          <summary>Forgot password?</summary>
          <p>
            Contact your administrator to verify your identity and reset your
            password. Share your username; do not share passwords.
          </p>
          <p>
            After receiving your new password securely, sign in and choose your
            own password in Settings → Change password.
          </p>
        </details>
        <footer className="signin-footer">
          Need an account? Contact your administrator.
        </footer>
      </section>
    </main>
  );
}
