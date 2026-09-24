import type { PresenceStatus } from "../../types/chat";

export const statusLabels: Record<PresenceStatus, string> = {
  online: "Online",
  offline: "Offline",
  meeting: "In a meeting",
  busy: "Busy",
};
