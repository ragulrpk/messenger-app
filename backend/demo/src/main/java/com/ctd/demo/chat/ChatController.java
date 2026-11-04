package com.ctd.demo.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
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
    private final ChatNotifications notifications;
    /** Receives the services that own chat data, access checks, and stored files. */
    public ChatController(ChatService chat, ChatFileService files, FileStorage storage, ChatNotifications notifications) {
        this.chat = chat;
        this.files = files;
        this.storage = storage;
        this.notifications = notifications;
    }

    @GetMapping("/notifications")
    public Object notifications(Authentication auth, @RequestParam(required = false) Long after) {
        return notifications.feed(auth.getName(), after);
    }

    public record DirectRequest(@NotNull UUID userId) { }
    public record TypingRequest(@NotNull Boolean active) { }
    public record SendRequest(@NotNull UUID clientId, @NotBlank @Size(max = 4000) String text, UUID replyToId) { }
    public record EditRequest(@NotNull @Size(max = 4000) String text) { }
    /** everyone=true unsends the caller's own message; false hides any message for the caller only. */
    public record DeleteRequest(@NotNull Boolean everyone) { }
    /** A null emoji removes the caller's reaction. */
    public record ReactionRequest(String emoji) { }
    public record ForwardRequest(@NotNull UUID conversationId, @NotNull UUID clientId) { }
    /** adminsOnly=true makes an announcement group, where only admins post. */
    public record GroupRequest(@NotBlank @Size(max = 100) String name,
                               @NotNull @Size(min = 1, max = 99) java.util.List<@NotNull UUID> memberIds,
                               Boolean adminsOnly) { }
    public record MembersRequest(@NotNull @Size(min = 1, max = 99) java.util.List<@NotNull UUID> userIds) { }
    public record SettingsRequest(@NotNull Boolean adminsOnly) { }
    public record LookupRequest(@NotNull @Size(max = 100) java.util.List<String> usernames) { }
    public record RenameRequest(@NotBlank @Size(max = 100) String name) { }
    public record RoleRequest(@NotNull @Pattern(regexp = "admin|member") String role) { }
    /** Omitted fields keep their current value. */
    public record PreferencesRequest(Boolean pinned, Boolean muted, Boolean hidden, Boolean unread) { }
    /** Without a sequence, every message in the conversation is marked read. */
    public record ReadRequest(@PositiveOrZero Long sequence) { }
    public record PresenceRequest(@NotNull @Pattern(regexp = "online|offline|meeting|busy") String status,
                                  @NotNull @Size(max = 160) String note) { }

    /** Searches other enabled users in the directory. */
    @GetMapping("/users")
    public Object users(Authentication auth, @RequestParam(defaultValue = "") String q) {
        return chat.directory(auth.getName(), q);
    }
    /** Finds many people at once by username; unknown names are simply left out. */
    @PostMapping("/users/lookup")
    public Object lookup(Authentication auth, @Valid @RequestBody LookupRequest request) {
        return chat.lookup(auth.getName(), request.usernames());
    }
    /** Returns a page of the caller's conversations. */
    @GetMapping("/conversations")
    public Object conversations(Authentication auth,
            @RequestParam(required = false) @Size(max = 256) String cursor) {
        return chat.conversations(auth.getName(), cursor);
    }
    /** Marks everything sent to the caller so far as delivered to their device. */
    @PostMapping("/delivered")
    public Object delivered(Authentication auth) {
        chat.delivered(auth.getName());
        return java.util.Map.of("delivered", true);
    }
    @GetMapping("/conversations/search")
    public Object searchGroups(Authentication auth, @RequestParam(defaultValue = "") @Size(max = 100) String q) {
        return chat.searchGroups(auth.getName(), q);
    }
    @GetMapping("/conversations/{id}")
    public Object conversation(Authentication auth, @PathVariable UUID id) { return chat.conversation(auth.getName(), id); }

    @PostMapping("/conversations/{id}/clear")
    public Object clear(Authentication auth, @PathVariable UUID id) {
        return chat.clear(auth.getName(), id);
    }

    @GetMapping("/conversations/{id}/files")
    public Object sharedFiles(Authentication auth, @PathVariable UUID id, @RequestParam(required = false) Long before) {
        return chat.sharedFiles(auth.getName(), id, before);
    }

    @GetMapping("/conversations/{id}/messages/search")
    public Object searchMessages(Authentication auth, @PathVariable UUID id,
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(required = false) Long before) {
        return chat.searchMessages(auth.getName(), id, q, before);
    }

    /** Opens or creates a direct conversation with the requested user. */
    @PostMapping("/conversations/direct")
    public Object direct(Authentication auth, @Valid @RequestBody DirectRequest request) {
        return chat.direct(auth.getName(), request.userId());
    }
    /** Creates a group with the caller as admin. */
    @PostMapping("/conversations/group")
    public Object createGroup(Authentication auth, @Valid @RequestBody GroupRequest request) {
        return chat.createGroup(auth.getName(), request.name(), request.memberIds(),
                Boolean.TRUE.equals(request.adminsOnly()));
    }
    /** Turns announcement mode on or off (admins only). */
    @PostMapping("/conversations/{id}/settings")
    public Object settings(Authentication auth, @PathVariable UUID id, @Valid @RequestBody SettingsRequest request) {
        return chat.setAdminsOnly(auth.getName(), id, request.adminsOnly());
    }
    /** Lists the current members of a conversation. */
    @GetMapping("/conversations/{id}/members")
    public Object members(Authentication auth, @PathVariable UUID id) {
        return chat.members(auth.getName(), id);
    }
    /** Adds people to a group (admins only). */
    @PostMapping("/conversations/{id}/members")
    public Object addMembers(Authentication auth, @PathVariable UUID id, @Valid @RequestBody MembersRequest request) {
        return chat.addMembers(auth.getName(), id, request.userIds());
    }
    /** Removes a member from a group (admins only). */
    @PostMapping("/conversations/{id}/members/{userId}/remove")
    public Object removeMember(Authentication auth, @PathVariable UUID id, @PathVariable UUID userId) {
        return chat.removeMember(auth.getName(), id, userId);
    }
    /** Makes a member an admin or a plain member (admins only). */
    @PostMapping("/conversations/{id}/members/{userId}/role")
    public Object setRole(Authentication auth, @PathVariable UUID id, @PathVariable UUID userId,
            @Valid @RequestBody RoleRequest request) {
        return chat.setRole(auth.getName(), id, userId, request.role());
    }
    /** Renames a group (admins only). */
    @PostMapping("/conversations/{id}/rename")
    public Object rename(Authentication auth, @PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        return chat.rename(auth.getName(), id, request.name());
    }
    /** Leaves a group. */
    @PostMapping("/conversations/{id}/leave")
    public Object leave(Authentication auth, @PathVariable UUID id) {
        chat.leave(auth.getName(), id);
        return java.util.Map.of("left", true);
    }
    /** Returns a page of messages around the requested cursor. */
    @GetMapping("/conversations/{id}/messages")
    public Object messages(Authentication auth, @PathVariable UUID id,
            @RequestParam(required = false) Long before, @RequestParam(required = false) Long after,
            @RequestParam(required = false) Long changedAfter) {
        return chat.history(auth.getName(), id, before, after, changedAfter);
    }
    @PostMapping("/conversations/{id}/typing")
    public Object typing(Authentication auth, @PathVariable UUID id, @Valid @RequestBody TypingRequest request) {
        chat.typing(auth.getName(), id, request.active());
        return java.util.Map.of("accepted", true);
    }

    /** Saves the caller's pin, mute, remove and manual-unread choices for a conversation. */
    @PostMapping("/conversations/{id}/preferences")
    public Object preferences(Authentication auth, @PathVariable UUID id, @RequestBody PreferencesRequest request) {
        return chat.preferences(auth.getName(), id, request.pinned(), request.muted(), request.hidden(), request.unread());
    }
    /** Records how far the caller has read a conversation. */
    @PostMapping("/conversations/{id}/read")
    public Object read(Authentication auth, @PathVariable UUID id, @Valid @RequestBody ReadRequest request) {
        return chat.read(auth.getName(), id, request.sequence());
    }
    /** Returns the caller's own status and note. */
    @GetMapping("/presence")
    public Object presence(Authentication auth) {
        return chat.presence(auth.getName());
    }
    /** Updates the caller's status and note, which their contacts see. */
    @PostMapping("/presence")
    public Object updatePresence(Authentication auth, @Valid @RequestBody PresenceRequest request) {
        return chat.updatePresence(auth.getName(), request.status(), request.note());
    }

    /** Sends a validated message to a conversation. */
    @PostMapping("/conversations/{id}/messages")
    public Object send(Authentication auth, @PathVariable UUID id, @Valid @RequestBody SendRequest request) {
        return chat.send(auth.getName(), id, request.clientId(), request.text(), request.replyToId());
    }

    /** Uploads up to five files (each sent as a "file" part), with an optional caption, as one message. */
    @PostMapping(path = "/conversations/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(Authentication auth, @PathVariable UUID id, @RequestParam UUID clientId,
            @RequestParam(defaultValue = "") @Size(max = 4000) String text,
            @RequestPart("file") java.util.List<MultipartFile> file,
            @RequestParam(required = false) UUID replyToId) {
        return files.upload(auth.getName(), id, clientId, text, file, replyToId);
    }

    /** Replaces the text of the caller's own message. */
    @PostMapping("/messages/{messageId}/edit")
    public Object edit(Authentication auth, @PathVariable UUID messageId, @Valid @RequestBody EditRequest request) {
        return chat.edit(auth.getName(), messageId, request.text());
    }
    /** Deletes a message for everyone (own messages only) or for the caller only. */
    @PostMapping("/messages/{messageId}/delete")
    public Object delete(Authentication auth, @PathVariable UUID messageId, @Valid @RequestBody DeleteRequest request) {
        chat.delete(auth.getName(), messageId, request.everyone());
        return java.util.Map.of("deleted", true);
    }
    /** Sets or clears the caller's reaction to a message. */
    @PostMapping("/messages/{messageId}/reaction")
    public Object react(Authentication auth, @PathVariable UUID messageId, @RequestBody ReactionRequest request) {
        return chat.react(auth.getName(), messageId, request.emoji());
    }
    /** Copies a message, and any file it carries, into another of the caller's conversations. */
    @PostMapping("/messages/{messageId}/forward")
    public Object forward(Authentication auth, @PathVariable UUID messageId, @Valid @RequestBody ForwardRequest request) {
        return files.forward(auth.getName(), messageId, request.conversationId(), request.clientId());
    }

    /** Downloads a file for conversation members; browsers always save it rather than render it as a page.
     *  A file's bytes never change under its ID, so the browser keeps a private copy for a day instead of
     *  fetching image previews again on every view; after that, a revalidation that still passes the
     *  membership check above gets a bodiless 304. */
    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> download(Authentication auth, @PathVariable UUID fileId) {
        var file = chat.download(auth.getName(), fileId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .eTag(fileId.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(storage.resolve(file.storageKey())));
    }
}
