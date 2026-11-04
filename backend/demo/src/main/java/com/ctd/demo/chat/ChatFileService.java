package com.ctd.demo.chat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Validates uploads, writes them to storage outside the database transaction, and attaches them to messages. */
@Service
public class ChatFileService {
    // The server decides the content type from the extension; the browser-supplied type is never trusted.
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("zip", "application/zip"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("ogg", "audio/ogg"),
            // Code and markup are served as plain text so a browser never runs or renders them.
            Map.entry("json", "text/plain"),
            Map.entry("xml", "text/plain"),
            Map.entry("yaml", "text/plain"),
            Map.entry("yml", "text/plain"),
            Map.entry("md", "text/plain"),
            Map.entry("log", "text/plain"),
            Map.entry("sql", "text/plain"),
            Map.entry("py", "text/plain"),
            Map.entry("js", "text/plain"),
            Map.entry("ts", "text/plain"),
            Map.entry("java", "text/plain"),
            Map.entry("html", "text/plain"),
            Map.entry("css", "text/plain"),
            Map.entry("rtf", "application/rtf"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"),
            Map.entry("odp", "application/vnd.oasis.opendocument.presentation"),
            Map.entry("7z", "application/x-7z-compressed"),
            Map.entry("tar", "application/x-tar"),
            Map.entry("gz", "application/gzip"));
    // Videos may use app.files.video-max-size; every other file is held to app.files.max-size.
    private static final Set<String> VIDEOS = Set.of("mp4", "mov", "webm");
    private final ChatService chat;
    private final FileStorage storage;
    private final ChatFileProperties properties;

    public ChatFileService(ChatService chat, FileStorage storage, ChatFileProperties properties) {
        this.chat = chat;
        this.storage = storage;
        this.properties = properties;
    }

    /** Stores the files sent together as one message, in order; a retry with the same client ID and bytes
     *  returns the original message. Every file is checked before any is written. */
    public ChatService.Message upload(String username, UUID conversation, UUID clientId, String caption,
            List<MultipartFile> uploads, UUID replyTo) {
        if (uploads == null || uploads.isEmpty() || uploads.size() > ChatService.MAX_FILES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Send between 1 and " + ChatService.MAX_FILES + " files at a time.");
        long used = chat.checkUpload(username, conversation);
        var names = new ArrayList<String>();
        long total = 0;
        for (MultipartFile file : uploads) {
            String name = cleanName(file.getOriginalFilename());
            if (!properties.allowedExtensions().contains(extension(name)))
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This file type is not allowed.");
            if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty.");
            names.add(name);
            total += file.getSize();
        }
        requireQuota(used, total);
        var stored = new ArrayList<FileStorage.Blob>();
        boolean attached = false;
        try {
            var newFiles = new ArrayList<ChatService.NewFile>();
            for (int i = 0; i < uploads.size(); i++) {
                String name = names.get(i);
                String extension = extension(name);
                FileStorage.Blob blob;
                try (InputStream input = uploads.get(i).getInputStream()) {
                    blob = storage.store(input,
                            (VIDEOS.contains(extension) ? properties.videoMaxSize() : properties.maxSize()).toBytes());
                } catch (IOException exception) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "File storage is unavailable.", exception);
                }
                stored.add(blob);
                if (!signatureMatches(extension, blob.header()))
                    throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "File content does not match its type.");
                newFiles.add(new ChatService.NewFile(blob.key(), name, TYPES.getOrDefault(extension, "application/octet-stream"),
                        blob.size(), blob.sha256(), Instant.now().plus(properties.retention())));
            }
            var sent = chat.sendFiles(username, conversation, clientId, caption, newFiles, replyTo);
            attached = sent.created();
            return sent.message();
        } finally {
            // Retries, rejections, and rollbacks must not leave unreferenced bytes on the volume.
            if (!attached) stored.forEach(blob -> storage.delete(blob.key()));
        }
    }

    /** Forwards a message into another conversation. Its files are copied, so each copy expires on its own
     *  and deleting one never removes the other. */
    public ChatService.Message forward(String username, UUID messageId, UUID target, UUID clientId) {
        var source = chat.forwardSource(username, messageId);
        long used = chat.checkUpload(username, target);
        if (source.files().isEmpty()) return chat.sendForward(username, target, clientId, source.text(), List.of()).message();
        // The copies are new storage in the forwarder's name, so they count toward their quota.
        requireQuota(used, source.files().stream().mapToLong(ChatFileRepository.StoredFile::size).sum());
        var stored = new ArrayList<FileStorage.Blob>();
        boolean attached = false;
        try {
            var copies = new ArrayList<ChatService.NewFile>();
            for (var file : source.files()) {
                FileStorage.Blob blob;
                try (InputStream input = Files.newInputStream(storage.resolve(file.storageKey()))) {
                    blob = storage.store(input, Long.MAX_VALUE);
                } catch (IOException exception) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "File storage is unavailable.", exception);
                }
                stored.add(blob);
                copies.add(new ChatService.NewFile(blob.key(), file.name(), file.contentType(), blob.size(), blob.sha256(),
                        Instant.now().plus(properties.retention())));
            }
            var sent = chat.sendForward(username, target, clientId, source.text(), copies);
            attached = sent.created();
            return sent.message();
        } finally {
            if (!attached) stored.forEach(blob -> storage.delete(blob.key()));
        }
    }

    /** Caps the bytes one person can add to the shared volume per day. Concurrent uploads can overshoot it by
     *  at most what is in flight; it bounds storage abuse, it is not an exact accounting. */
    private void requireQuota(long used, long size) {
        if (used + size > properties.dailyQuota().toBytes())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Daily upload limit reached. Try again tomorrow.");
    }

    /** Keeps only the final path segment, removes control characters, and limits the length. */
    static String cleanName(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals(".."))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File name is missing.");
        if (name.length() > 255) {
            String extension = extension(name);
            name = name.substring(0, 254 - extension.length()) + "." + extension;
        }
        return name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Confirms that images and PDFs start with their format's signature, since browsers render them. */
    static boolean signatureMatches(String extension, byte[] header) {
        return switch (extension) {
            case "png" -> startsWith(header, new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });
            case "jpg", "jpeg" -> startsWith(header, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF });
            case "gif" -> startsWith(header, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                    || startsWith(header, "GIF89a".getBytes(StandardCharsets.US_ASCII));
            case "webp" -> startsWith(header, "RIFF".getBytes(StandardCharsets.US_ASCII)) && header.length >= 12
                    && Arrays.equals(Arrays.copyOfRange(header, 8, 12), "WEBP".getBytes(StandardCharsets.US_ASCII));
            case "pdf" -> startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            default -> true;
        };
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }
}
