import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import AuthProvider from "./context/AuthProvider";
import "./index.css";
import "./App.css";
import App from "./App.jsx";
import { initializeTheme } from "./context/theme";
import ErrorBoundary from "./components/ErrorBoundary.jsx";
import "./theme.css";

initializeTheme();

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </ErrorBoundary>
  </StrictMode>,
);
