import { useSyncExternalStore } from "react";

export type Theme = "light" | "dark";
const storageKey = "messenger.theme";
let currentTheme: Theme = "light";
const listeners = new Set<() => void>();

// Apply a theme to the page and notify subscribed components.
function applyTheme(theme: Theme) {
  currentTheme = theme;
  document.documentElement.dataset.theme = theme;
  listeners.forEach((listener) => listener());
}

// Read the saved theme, defaulting to light when storage is unavailable.
function savedTheme(): Theme {
  try {
    return window.localStorage.getItem(storageKey) === "dark"
      ? "dark"
      : "light";
  } catch {
    return "light";
  }
}

// Run before React mounts; keep syncing even on the signed-out login screen.
// Apply the saved theme and listen for changes from other tabs.
export function initializeTheme() {
  applyTheme(savedTheme());
  const onStorage = (event: StorageEvent) => {
    if (event.key === storageKey || event.key === null)
      applyTheme(savedTheme());
  };
  window.addEventListener("storage", onStorage);
  return () => window.removeEventListener("storage", onStorage);
}

// Apply and persist the selected theme.
export function setTheme(theme: Theme) {
  applyTheme(theme);
  try {
    window.localStorage.setItem(storageKey, theme);
  } catch {
    /* The theme still applies when browser storage is unavailable. */
  }
}

// Register a listener for theme changes and return its cleanup.
function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

// Subscribe a React component to the current theme.
export function useTheme() {
  return useSyncExternalStore(
    subscribe,
    () => currentTheme,
    () => "light" as Theme,
  );
}
