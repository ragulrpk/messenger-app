import type { PresenceStatus } from "../../types/chat";
import { statusLabels } from "./presence";
// Render a profile image or initials with presence styling.
export default function Avatar({
  name,
  group = false,
  online,
  url,
  status,
}: {
  name: string;
  group?: boolean;
  online?: boolean;
  url?: string;
  status?: PresenceStatus;
}) {
  return (
    <span className={`chat-avatar ${group ? "group-avatar" : ""}`}>
      {url ? (
        <img src={url} alt="" />
      ) : group ? (
        "#"
      ) : (
        name
          .split(" ")
          .map((w) => w.replace(/[^\p{L}\p{N}]/gu, "")[0])
          .filter(Boolean)
          .slice(0, 2)
          .join("")
          .toUpperCase()
      )}
      {(status || online !== undefined) && (
        <span
          className={"presence " + (status ?? (online ? "online" : "offline"))}
          role="img"
          aria-label={statusLabels[status ?? (online ? "online" : "offline")]}
        />
      )}
    </span>
  );
}
