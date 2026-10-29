package com.ctd.demo.chat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
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
            Map.entry("zip", "application/zip"));
    private final ChatService chat;
    private final FileStorage storage;
    private final ChatFileProperties properties;

    public ChatFileService(ChatService chat, FileStorage storage, ChatFileProperties properties) {
        this.chat = chat;
        this.storage = storage;
        this.properties = properties;
    }

    /** Stores a file message; a retry with the same client ID and bytes returns the original message. */
    public ChatService.Message upload(String username, UUID conversation, UUID clientId, String caption, MultipartFile file) {
        chat.requireMember(username, conversation);
        String name = cleanName(file.getOriginalFilename());
        String extension = extension(name);
        if (!properties.allowedExtensions().contains(extension))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This file type is not allowed.");
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty.");
        FileStorage.Blob blob;
        try (InputStream input = file.getInputStream()) {
            blob = storage.store(input, properties.maxSize().toBytes());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "File storage is unavailable.", exception);
        }
        boolean attached = false;
        try {
            if (!signatureMatches(extension, blob.header()))
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "File content does not match its type.");
            var sent = chat.sendFile(username, conversation, clientId, caption, new ChatService.NewFile(blob.key(), name,
                    TYPES.getOrDefault(extension, "application/octet-stream"), blob.size(), blob.sha256(),
                    Instant.now().plus(properties.retention())));
            attached = sent.created();
            return sent.message();
        } finally {
            // Retries, rejections, and rollbacks must not leave unreferenced bytes on the volume.
            if (!attached) storage.delete(blob.key());
        }
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
