import { Navigate } from "react-router-dom";
import { useAuth } from "../../context/useAuth";
import ChatPage from "./ChatPage";
// Guard the chat route and pass session actions to the chat page.
export default function Chat() {
  const { user, signOut, initializing, signingOut, logoutError } = useAuth();
  if (initializing)
    return (
      <main aria-busy="true" role="status">
        Restoring your session…
      </main>
    );
  if (!user) return <Navigate to="/login" replace />;
  return (
    <ChatPage
      key={user.id}
      user={user}
      signOut={signOut}
      signingOut={signingOut}
      logoutError={logoutError}
    />
  );
}
