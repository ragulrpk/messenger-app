package com.ctd.demo.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Stores uploaded bytes on the shared file volume under random, date-partitioned keys. */
@Component
public class FileStorage {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private final Path root;
    private final Path incoming;

    /** A stored upload with the first bytes kept for content checks. */
    public record Blob(String key, long size, String sha256, byte[] header) { }

    /** Creates the storage directories at startup so a missing or read-only volume fails fast. */
    public FileStorage(ChatFileProperties properties) throws IOException {
        this.root = properties.root().toAbsolutePath().normalize();
        // Temporary uploads stay on the same volume so the final move is atomic.
        this.incoming = Files.createDirectories(root.resolve(".incoming"));
    }

    /** Streams an upload to disk, rejecting it once it exceeds the byte limit. */
    public Blob store(InputStream input, long maxBytes) throws IOException {
        Path temp = Files.createTempFile(incoming, "upload-", ".part");
        try {
            MessageDigest digest = sha256();
            byte[] header = new byte[16];
            int headerLength = 0;
            long size = 0;
            try (var in = new DigestInputStream(input, digest); OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    size += read;
                    if (size > maxBytes) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "File is too large.");
                    if (headerLength < header.length) {
                        int copied = Math.min(read, header.length - headerLength);
                        System.arraycopy(buffer, 0, header, headerLength, copied);
                        headerLength += copied;
                    }
                    out.write(buffer, 0, read);
                }
            }
            String key = LocalDate.now(ZoneOffset.UTC).format(DAY) + "/" + UUID.randomUUID();
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return new Blob(key, size, HexFormat.of().formatHex(digest.digest()), Arrays.copyOf(header, headerLength));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Resolves a key to its file, refusing anything outside the storage root. */
    public Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.startsWith(incoming)) throw new IllegalArgumentException("Invalid storage key");
        return path;
    }

    /** Removes stored bytes; deleting an already missing file is not an error. */
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Removes partial uploads left behind by a crashed or killed replica. */
    public int deleteStaleUploads(Instant olderThan) throws IOException {
        int deleted = 0;
        try (Stream<Path> parts = Files.list(incoming)) {
            for (Path part : parts.toList()) {
                if (Files.getLastModifiedTime(part).toInstant().isBefore(olderThan) && Files.deleteIfExists(part)) deleted++;
            }
        }
        return deleted;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
