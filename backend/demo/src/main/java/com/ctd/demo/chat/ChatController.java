package com.ctd.demo.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

/** Routes authenticated directory, conversation, and message requests to chat services. */
@RestController
@RequestMapping("/api/v1/chat")
@Validated
public class ChatController {
    private final ChatService chat;
    private final ChatFileService files;
    private final FileStorage storage;
    /** Receives the services that own chat data, access checks, and stored files. */
    public ChatController(ChatService chat, ChatFileService files, FileStorage storage) {
        this.chat = chat;
        this.files = files;
        this.storage = storage;
    }

    public record DirectRequest(@NotNull UUID userId) { }
    public record TypingRequest(@NotNull Boolean active) { }
    public record SendRequest(@NotNull UUID clientId, @NotBlank @Size(max = 4000) String text) { }

    /** Searches other enabled users in the directory. */
    @GetMapping("/users")
    public Object users(Authentication auth, @RequestParam(defaultValue = "") String q) {
        return chat.directory(auth.getName(), q);
    }
    /** Returns a page of the caller's conversations. */
    @GetMapping("/conversations")
    public Object conversations(Authentication auth,
            @RequestParam(required = false) @Size(max = 256) String cursor) {
        return chat.conversations(auth.getName(), cursor);
    }
    /** Opens or creates a direct conversation with the requested user. */
    @PostMapping("/conversations/direct")
    public Object direct(Authentication auth, @Valid @RequestBody DirectRequest request) {
        return chat.direct(auth.getName(), request.userId());
    }
    /** Returns a page of messages around the requested cursor. */
    @GetMapping("/conversations/{id}/messages")
    public Object messages(Authentication auth, @PathVariable UUID id,
            @RequestParam(required = false) Long before, @RequestParam(required = false) Long after) {
        return chat.history(auth.getName(), id, before, after);
    }
    @PostMapping("/conversations/{id}/typing")
    public Object typing(Authentication auth, @PathVariable UUID id, @Valid @RequestBody TypingRequest request) {
        chat.typing(auth.getName(), id, request.active());
        return java.util.Map.of("accepted", true);
    }

    /** Sends a validated message to a conversation. */
    @PostMapping("/conversations/{id}/messages")
    public Object send(Authentication auth, @PathVariable UUID id, @Valid @RequestBody SendRequest request) {
        return chat.send(auth.getName(), id, request.clientId(), request.text());
    }

    /** Uploads one file, with an optional caption, as a message in the conversation. */
    @PostMapping(path = "/conversations/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(Authentication auth, @PathVariable UUID id, @RequestParam UUID clientId,
            @RequestParam(defaultValue = "") @Size(max = 4000) String text, @RequestPart MultipartFile file) {
        return files.upload(auth.getName(), id, clientId, text, file);
    }

    /** Downloads a file for conversation members; browsers always save it rather than render it as a page. */
    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> download(Authentication auth, @PathVariable UUID fileId) {
        var file = chat.download(auth.getName(), fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(storage.resolve(file.storageKey())));
    }
}
