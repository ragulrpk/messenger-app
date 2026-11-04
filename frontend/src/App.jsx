import { Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import Login from "./pages/login/Login";
import ForgotPassword from "./pages/login/ForgotPassword";
import Chat from "./pages/chat/Chat";
import { useAuth } from "./context/useAuth";
import Profile from "./pages/profile/Profile";
import Administration from "./pages/admin/Administration";
import IdleGuard from "./components/IdleGuard";

// Protect account pages and preserve the chat view while showing the profile.
function AccountPages() {
  const { user, initializing } = useAuth();
  const { pathname } = useLocation();
  if (initializing)
    return (
      <main aria-busy="true" role="status">
        Restoring your session…
      </main>
    );
  if (!user) return <Navigate to="/login" replace />;
  // Keep local drafts, messages, attachments and settings when visiting the profile.
  return (
    <>
      <IdleGuard />
      <div hidden={pathname !== "/chat"}>
        <Chat />
      </div>
      <Outlet />
    </>
  );
}

// Sign-in pages for people who are signed out; a signed-in person goes on to the app.
function SignedOutRoute({ children }) {
  const { user, initializing } = useAuth();
  if (initializing)
    return (
      <main aria-busy="true" role="status">
        Restoring your session…
      </main>
    );
  if (user)
    return <Navigate to={user.administrator ? "/admin" : "/chat"} replace />;
  return children;
}

// Define the application routes and their authentication guards.
export default function App() {
  return (
    <Routes>
      <Route
        path="/"
        element={
          <SignedOutRoute>
            <Login />
          </SignedOutRoute>
        }
      />
      <Route
        path="/login"
        element={
          <SignedOutRoute>
            <Login />
          </SignedOutRoute>
        }
      />
      <Route
        path="/admin/login"
        element={
          <SignedOutRoute>
            <Login admin />
          </SignedOutRoute>
        }
      />
      <Route
        path="/forgot-password"
        element={
          <SignedOutRoute>
            <ForgotPassword />
          </SignedOutRoute>
        }
      />
      <Route element={<AccountPages />}>
        <Route path="/chat" element={null} />
        <Route path="/profile" element={<Profile />} />
        <Route path="/admin" element={<Administration />} />
        {/* The old reset page's address now opens the administration page. */}
        <Route
          path="/admin/password-reset"
          element={<Navigate to="/admin" replace />}
        />
      </Route>
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
