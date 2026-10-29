import { fileUrl } from "../../services/chatService";
import { fileSize } from "./chatState";
import type { ChatAttachment } from "../../types/chat";

// Show a shared file as a download link, with a preview for images.
export default function FileAttachment({
  attachment: a,
}: {
  attachment: ChatAttachment;
}) {
  if (!a.available)
    return (
      <span className="file-attachment expired">
        <span aria-hidden="true">⌀</span>
        <span>
          <strong>{a.name}</strong>
          <small>File expired</small>
        </span>
      </span>
    );
  const url = fileUrl(a.id);
  return (
    <a
      className="file-attachment"
      href={url}
      download={a.name}
      aria-label={`Download ${a.name}`}
    >
      {a.contentType.startsWith("image/") ? (
        <img className="file-preview" src={url} alt="" loading="lazy" />
      ) : (
        <span aria-hidden="true">↓</span>
      )}
      <span>
        <strong>{a.name}</strong>
        <small>{fileSize(a.size)} · Download</small>
      </span>
    </a>
  );
}
