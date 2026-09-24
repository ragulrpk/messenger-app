import { createContext, useContext } from "react";

export const AuthContext = createContext(null);

// Read authentication state from the nearest provider.
// Throws an error if no provider is found.
// This hook is used in App.jsx to protect account pages and redirect unauthenticated users to the login page.
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within AuthProvider.");
  return context;
}
