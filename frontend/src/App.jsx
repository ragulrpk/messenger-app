import { Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import Login from "./pages/login/Login";
import Chat from "./pages/chat/Chat";
import { useAuth } from "./context/useAuth";
import Profile from "./pages/profile/Profile";
import AdminPasswordReset from "./pages/admin/AdminPasswordReset";

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
      <div hidden={pathname !== "/chat"}>
        <Chat />
      </div>
      <Outlet />
    </>
  );
}

// Show the login page or redirect an authenticated user to chat.
//Alwats initializing will be empty because user it trying to login and the user is not authenticated yet. So it will always show the login page.
function LoginRoute() {
  const { user, initializing } = useAuth();
  if (initializing)
    return (
      <main aria-busy="true" role="status">
        Restoring your session…
      </main>
    );
  return user ? <Navigate to="/chat" replace /> : <Login />;
}

// Define the application routes and their authentication guards.
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LoginRoute />} />
      <Route path="/login" element={<LoginRoute />} />
      <Route element={<AccountPages />}>
        <Route path="/chat" element={null} />
        <Route path="/profile" element={<Profile />} />
        <Route path="/admin/password-reset" element={<AdminPasswordReset />} />
      </Route>
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
