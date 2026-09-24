import { Component } from "react";

/** Displays a safe recovery screen when an unexpected render error reaches the app root. */
export default class ErrorBoundary extends Component {
  state = { failed: false };

  /** Switches to the recovery screen after a descendant throws during rendering. */
  static getDerivedStateFromError() {
    return { failed: true };
  }

  /** Records the failure without exposing user data or implementation details in the UI. */
  componentDidCatch(error) {
    console.error("Unexpected application error", error);
  }

  /** Reloads the application so temporary client state is rebuilt from the server. */
  reload = () => {
    window.location.reload();
  };

  /** Renders either the application or its recovery screen. */
  render() {
    if (!this.state.failed) return this.props.children;

    return (
      <main className="fatal-error" role="alert">
        <section className="fatal-error__card">
          <h1>Something went wrong</h1>
          <p>
            The application could not display this page. Reload it to try again.
          </p>
          <button type="button" onClick={this.reload}>
            Reload application
          </button>
        </section>
      </main>
    );
  }
}
