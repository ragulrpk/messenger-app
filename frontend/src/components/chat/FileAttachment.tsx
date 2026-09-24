import { useEffect, useRef } from "react";

// Create a temporary download link and display attachment details.
export default function FileAttachment({ file }: { file: File }) {
  const link = useRef<HTMLAnchorElement>(null);
  useEffect(() => {
    const url = URL.createObjectURL(
      new Blob([file], { type: "application/octet-stream" }),
    );
    if (link.current) link.current.href = url;
    return () => URL.revokeObjectURL(url);
  }, [file]);
  const size =
    file.size < 1024 * 1024
      ? `${Math.ceil(file.size / 1024)} KB`
      : `${(file.size / (1024 * 1024)).toFixed(1)} MB`;
  return (
    <a
      ref={link}
      className="file-attachment"
      download={file.name}
      aria-label={`Download ${file.name}`}
    >
      <span aria-hidden="true">↓</span>
      <span>
        <strong>{file.name}</strong>
        <small>{size} · Download</small>
      </span>
    </a>
  );
}
