package com.ctd.demo.chat;

import com.ctd.demo.user.UserRepository;
import com.ctd.demo.user.UserDirectoryRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.ApplicationEventPublisher;

/** Enforces chat access and stores direct conversations and messages. */
@Service
@Transactional
public class ChatService {
    private final ChatRepository chats;
    private final ChatNotifications notifications;
    private final ChatFileRepository files;
    private final UserDirectoryRepository directory;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private static final int PAGE = 50;

    public ChatService(ChatRepository chats, ChatFileRepository files, UserDirectoryRepository directory,
                       UserRepository users, ApplicationEventPublisher events, ChatNotifications notifications) {
        this.chats = chats;
        this.files = files;
        this.directory = directory;
        this.users = users;
        this.events = events;
        this.notifications = notifications;
    }
    public record Person(UUID id, String username, String name) { }
    /** A message as members see it: a deleted one keeps its place but loses its text and files.
     *  attachments holds every file sent with the message, in order; attachment is the first of them. */
    public record Message(UUID id, long sequence, UUID senderId, String senderName, UUID clientId,
                          String text, Instant sentAt, Attachment attachment, List<Attachment> attachments,
                          boolean edited, boolean deleted, boolean forwarded, Reply replyTo, List<Reaction> reactions,
                          List<Mention> mentions) {
        Message withReactions(List<Reaction> value) {
            return new Message(id, sequence, senderId, senderName, clientId, text, sentAt, attachment, attachments,
                    edited, deleted, forwarded, replyTo, List.copyOf(value), mentions);
        }
        Message quotingFrom(long historyFrom) {
            return replyTo == null ? this : new Message(id, sequence, senderId, senderName, clientId, text, sentAt,
                    attachment, attachments, edited, deleted, forwarded, replyTo.hiddenBefore(historyFrom), reactions, mentions);
        }
        Message withMentions(List<Mention> value) {
            return new Message(id, sequence, senderId, senderName, clientId, text, sentAt, attachment, attachments,
                    edited, deleted, forwarded, replyTo, reactions, List.copyOf(value));
        }
        /** Replaces the single joined attachment with every file of the message; no list keeps it as loaded. */
        Message withAttachments(List<Attachment> value) {
            return value == null || value.isEmpty() ? this : new Message(id, sequence, senderId, senderName, clientId,
                    text, sentAt, value.getFirst(), List.copyOf(value), edited, deleted, forwarded, replyTo, reactions, mentions);
        }
    }
    /** Someone a message mentions as "@username". The username is unique and never changes, so the
     *  mention keeps pointing at that person even if two people share a name, they are renamed, or they leave. */
    public record Mention(UUID id, String username, String name) { }
    /** "@" + a username (which may itself be an email address), not preceded by part of a word or address. */
    private static final java.util.regex.Pattern MENTION = java.util.regex.Pattern.compile(
            "(?<![\\p{L}\\p{N}._%+@-])@([\\p{L}\\p{N}._%+-]+(?:@[\\p{L}\\p{N}.-]+)?)");
    /** The message a reply quotes, shortened for display. */
    public record Reply(UUID id, String senderName, String text, String attachmentName, boolean deleted, long sequence) {
        /** A quote of a message from before the reader joined shows no content. */
        Reply hiddenBefore(long historyFrom) {
            return sequence > historyFrom ? this : new Reply(id, senderName, "Message from before you joined", null, false, sequence);
        }
    }
    /** How many members reacted with an emoji, and whether the caller is one of them. */
    public record Reaction(String emoji, int count, boolean mine) { }
    /** The reactions members can choose from. */
    public static final Set<String> REACTIONS = Set.of("👍", "❤️", "😂", "😮", "😢", "🙏");
    /** File details shown with a message; available turns false once the file expires. */
    public record Attachment(UUID id, String name, String contentType, long size, boolean available) { }
    /** A file already written to storage, waiting to be attached to a message. */
    public record NewFile(String storageKey, String name, String contentType, long size, String sha256, Instant expiresAt) { }
    /** A stored message and whether this call created it (false for an idempotent retry). */
    public record Sent(Message message, boolean created) { }
    /** The stored file a member may download. */
    public record Download(String storageKey, String name, String contentType, long size) { }
    /** A conversation row with the caller's own preferences and unread count, and the other person's status.
     *  The caller's messages up to targetDeliveredSequence reached the other person; up to targetReadSequence they were seen. */
    public record Conversation(UUID id, UUID targetId, String name, Instant updatedAt, Message lastMessage,
                               boolean pinned, boolean muted, boolean hidden, int unread,
                               String targetStatus, String targetNote,
                               long targetReadSequence, long targetDeliveredSequence,
                               String type, String role, int memberCount, boolean adminsOnly, long historyFromSequence,
                               boolean archived) { }
    /** A current member of a conversation and their role (admin or member). */
    public record Member(UUID id, String username, String name, String role) { }
    private static final int GROUP_LIMIT = 100;
    /** A user's self-chosen status (online, offline, meeting, busy) and personal note. */
    public record Presence(String status, String note) { }
    public record ConversationPage(List<Conversation> conversations, String nextCursor) { }
    /** A page of messages, plus, when asked for, the messages changed or removed since a revision. */
    public record MessagePage(List<Message> messages, boolean hasMore, long revision,
                              List<Message> changed, List<UUID> removed, boolean hasMoreChanges) { }
    /** A message to forward, with the stored files it carries, in order. */
    public record ForwardSource(String text, List<ChatFileRepository.StoredFile> files) { }
    /** The most files one message can carry. */
    public static final int MAX_FILES = 5;

    /** Resolves an enabled session user to its account ID. */
    private UUID actor(String username) {
        return users.findByUsername(username).filter(u -> u.isEnabled())
                .orElseThrow(() -> new BadCredentialsException("Invalid session")).getId();
    }
    /** Rejects access unless the user belongs to the conversation. */
    private void member(UUID user, UUID conversation) {
        if (!chats.isMember(user, conversation)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
        }
    }
    /** Finds enabled users matching a literal, case-insensitive search term. */
    @Transactional(readOnly = true)
    public List<Person> directory(String username, String query) {
        UUID user = actor(username);
        String term = query.trim().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) return List.of();
        if (term.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search is too long.");
        return directory.search(user, term);
    }
    /** Looks up many people at once by username, for adding a pasted list to a group. */
    @Transactional(readOnly = true)
    public List<Person> lookup(String username, List<String> usernames) {
        UUID user = actor(username);
        var wanted = new LinkedHashSet<String>();
        for (String name : usernames) {
            String normalized = name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
            if (!normalized.isEmpty() && normalized.length() <= 64) wanted.add(normalized);
        }
        return directory.findByUsernames(user, wanted);
    }
    /** Records that the caller's device has received everything sent to them so far, and tells the senders.
     *  Called by the app when a list load shows new arrivals, so routine list polls stay read-only. */
    public void delivered(String username) {
        UUID user = actor(username);
        for (var pending : chats.findUndelivered(user)) {
            long before = chats.findPositions(user, pending.conversation()).delivered();
            chats.markDelivered(user, pending.conversation(), pending.sequence());
            // Only the senders of the newly delivered messages need fresh ticks, not the whole group.
            notify(chats.findSenderUsernames(pending.conversation(), before, pending.sequence(), user));
        }
    }
    /** Lists the caller's conversations in recent-update order. */
    @Transactional(readOnly = true)
    public ConversationPage conversations(String username, String cursor) {
        UUID user = actor(username);
        ConversationCursor decoded = cursor != null && !cursor.isBlank() ? decodeCursor(cursor) : null;
        var found = chats.findConversations(user, decoded == null ? null : decoded.updatedAt(),
                decoded == null ? null : decoded.id(), PAGE + 1);
        boolean more = found.size() > PAGE;
        var page = List.copyOf(found.subList(0, Math.min(PAGE, found.size())));
        String next = more ? encodeCursor(page.getLast()) : null;
        return new ConversationPage(page, next);
    }
    @Transactional(readOnly = true)
    public List<Conversation> searchGroups(String username, String query) {
        UUID user = actor(username);
        String term = query.strip().toLowerCase(Locale.ROOT);
        if (term.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search is too long.");
        return term.isEmpty() ? List.of() : chats.searchGroups(user, term);
    }

    private record ConversationCursor(Instant updatedAt, UUID id) { }

    private String encodeCursor(Conversation conversation) {
        String value = conversation.updatedAt() + "|" + conversation.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ConversationCursor decodeCursor(String cursor) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
            int separator = value.lastIndexOf('|');
            return new ConversationCursor(Instant.parse(value.substring(0, separator)),
                    UUID.fromString(value.substring(separator + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid conversation cursor.");
        }
    }
    /** Finds or safely creates the unique direct conversation for two users. */
    public Conversation direct(String username, UUID target) {
        UUID user = actor(username);
        if (user.equals(target)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another person.");
        users.findById(target).filter(u -> u.isEnabled())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found."));
        var pair = new ArrayList<>(List.of(user.toString(), target.toString()));
        Collections.sort(pair);
        // The same first-user lock is used by both callers, making direct creation race-safe.
        directory.lockById(UUID.fromString(pair.getFirst()));
        String key = String.join(":", pair);
        var existing = chats.findDirect(key);
        UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst();
        if (existing.isEmpty()) {
            chats.createDirect(id, key, user, target, Instant.now());
            changed(id);
        } else {
            // Opening a conversation the caller removed from the list brings it back.
            chats.unhide(id, user);
        }
        return chats.findConversation(user, id);
    }
    /** Creates a named group with the caller as its admin and the chosen people as members.
     *  In an announcement group (adminsOnly) only admins can post. */
    public Conversation createGroup(String username, String name, List<UUID> memberIds, boolean adminsOnly) {
        UUID user = actor(username);
        String title = groupName(name);
        var members = new LinkedHashSet<>(memberIds);
        members.remove(user);
        if (members.isEmpty() || members.size() >= GROUP_LIMIT)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose between 1 and " + (GROUP_LIMIT - 1) + " other people.");
        members.forEach(this::requireEnabled);
        UUID id = UUID.randomUUID();
        chats.createGroup(id, title, user, members, adminsOnly, Instant.now());
        changed(id);
        return chats.findConversation(user, id);
    }
    /** Turns announcement mode on or off: when on, only admins can post. */
    public Conversation setAdminsOnly(String username, UUID id, boolean adminsOnly) {
        UUID user = requireAdmin(username, id);
        chats.setAdminsOnly(id, adminsOnly);
        changed(id);
        return chats.findConversation(user, id);
    }
    /** Rejects a new message from a non-admin in an announcement group. */
    private void requirePoster(UUID user, UUID id) {
        if (chats.isArchived(id))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This group was archived by an administrator.");
        if (chats.isAdminsOnly(id) && !chats.findRole(id, user).filter("admin"::equals).isPresent())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can send messages in this group.");
    }
    private static String groupName(String name) {
        String title = name == null ? "" : name.strip();
        if (title.isEmpty() || title.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Group name must contain 1–100 characters.");
        return title;
    }
    private void requireEnabled(UUID person) {
        users.findById(person).filter(u -> u.isEnabled())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found."));
    }
    /** Lists the current members of a conversation the caller belongs to. */
    @Transactional(readOnly = true)
    public List<Member> members(String username, UUID id) {
        member(actor(username), id);
        return chats.findMembers(id);
    }
    /** Checks that the conversation is a group and the caller is one of its admins; locks it for the change. */
    private UUID requireAdmin(String username, UUID id) {
        UUID user = actor(username);
        member(user, id);
        chats.lockConversation(id);
        requireGroup(id);
        if (!chats.findRole(id, user).filter("admin"::equals).isPresent())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only group admins can do this.");
        return user;
    }
    private void requireGroup(UUID id) {
        if (!"GROUP".equals(chats.findType(id)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is not a group.");
    }
    /** Adds people to a group, or brings back people who left. */
    public List<Member> addMembers(String username, UUID id, List<UUID> userIds) {
        requireAdmin(username, id);
        var current = chats.findMembers(id).stream().map(Member::id).toList();
        var added = new LinkedHashSet<>(userIds);
        added.removeAll(current);
        if (current.size() + added.size() > GROUP_LIMIT)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A group can have at most " + GROUP_LIMIT + " members.");
        added.forEach(this::requireEnabled);
        Instant now = Instant.now();
        for (UUID person : added) chats.addMember(id, person, now);
        chats.refreshMemberCount(id);
        changed(id);
        return chats.findMembers(id);
    }
    /** Removes another member from a group; they lose access to it straight away. */
    public List<Member> removeMember(String username, UUID id, UUID target) {
        UUID user = requireAdmin(username, id);
        if (user.equals(target)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use leave to exit the group.");
        String removed = activeUsername(id, target);
        chats.removeMember(id, target, Instant.now());
        chats.refreshMemberCount(id);
        changedAlso(id, removed);
        return chats.findMembers(id);
    }
    /** Makes a member an admin or a plain member; a group always keeps at least one admin. */
    public List<Member> setRole(String username, UUID id, UUID target, String role) {
        requireAdmin(username, id);
        activeUsername(id, target);
        if ("member".equals(role) && chats.findRole(id, target).filter("admin"::equals).isPresent()
                && chats.countAdmins(id) == 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A group needs at least one admin.");
        chats.setRole(id, target, role);
        changed(id);
        return chats.findMembers(id);
    }
    private String activeUsername(UUID id, UUID person) {
        return chats.findMembers(id).stream().filter(m -> m.id().equals(person)).map(Member::username).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found."));
    }
    public Conversation rename(String username, UUID id, String name) {
        UUID user = requireAdmin(username, id);
        chats.rename(id, groupName(name));
        changed(id);
        return chats.findConversation(user, id);
    }
    /** Leaves a group. If the last admin leaves, the longest-standing remaining member becomes admin. */
    public void leave(String username, UUID id) {
        UUID user = actor(username);
        member(user, id);
        chats.lockConversation(id);
        member(user, id);
        requireGroup(id);
        chats.removeMember(id, user, Instant.now());
        chats.refreshMemberCount(id);
        if (chats.countAdmins(id) == 0) chats.findEarliestMember(id).ifPresent(next -> chats.setRole(id, next, "admin"));
        changedAlso(id, username);
    }
    /** Notifies the current members and someone who just lost access. */
    private void changedAlso(UUID conversation, String formerMember) {
        var audience = new LinkedHashSet<>(chats.findMemberUsernames(conversation));
        audience.add(formerMember);
        events.publishEvent(new ChatChanged(List.copyOf(audience)));
    }
    @Transactional(readOnly = true)
    public Conversation conversation(String username, UUID id) {
        UUID user = actor(username); member(user, id); return chats.findConversation(user, id);
    }

    /** Clears only this member's history. New messages remain visible and other members are unaffected. */
    public Conversation clear(String username, UUID id) {
        UUID user = actor(username);
        member(user, id);
        chats.lockConversation(id);
        member(user, id);
        chats.clearHistory(user, id);
        events.publishEvent(new ChatChanged(List.of(username)));
        return chats.findConversation(user, id);
    }

    @Transactional(readOnly = true)
    public MessageSearchPage sharedFiles(String username, UUID id, Long before) {
        UUID user = actor(username);
        member(user, id);
        if (before != null && before < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file cursor.");
        var found = chats.findSharedFiles(user, id, before, PAGE + 1);
        return new MessageSearchPage(withReactions(user, id, found.subList(0, Math.min(PAGE, found.size()))), found.size() > PAGE);
    }

    public record MessageSearchPage(List<Message> messages, boolean hasMore) { }

    @Transactional(readOnly = true)
    public MessageSearchPage searchMessages(String username, UUID id, String query, Long before) {
        UUID user = actor(username);
        member(user, id);
        String term = query.strip().toLowerCase(Locale.ROOT);
        if (term.length() > 100 || (before != null && before < 1))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid message search.");
        if (term.isEmpty()) return new MessageSearchPage(List.of(), false);
        var found = chats.searchMessages(user, id, term, before, PAGE + 1);
        return new MessageSearchPage(withReactions(user, id, found.subList(0, Math.min(PAGE, found.size()))), found.size() > PAGE);
    }

    /** Loads older or newer messages after checking membership and cursors; with changedAfter it also
     *  returns the messages edited, deleted, reacted to, or deleted for the caller since that revision. */
    @Transactional(readOnly = true)
    public MessagePage history(String username, UUID id, Long before, Long after, Long changedAfter) {
        UUID user = actor(username);
        member(user, id);
        if ((before != null && after != null) || (before != null && before < 1) || (after != null && after < 0)
                || (changedAfter != null && changedAfter < 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid message cursor.");
        // Read the revision first: a change committed meanwhile is fetched again next time, never missed.
        long latestRevision = chats.findRevision(id);
        if (changedAfter != null && changedAfter > latestRevision)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid change cursor.");
        // Each mutation owns one revision under the conversation lock. Bound the revision interval,
        // not just the returned rows, so edits AND per-user removals share a safe continuation cursor.
        long revision = changedAfter == null ? latestRevision
                : changedAfter + Math.min(500L, latestRevision - changedAfter);
        var found = chats.findMessages(user, id, before, after, PAGE + 1);
        boolean more = found.size() > PAGE;
        var page = new ArrayList<>(found.subList(0, Math.min(PAGE, found.size())));
        if (after == null) Collections.reverse(page);
        List<Message> changed = List.of();
        List<UUID> removed = List.of();
        if (changedAfter != null && changedAfter < revision) {
            changed = withReactions(user, id, chats.findChanged(user, id, changedAfter, revision));
            removed = chats.findRemoved(user, id, changedAfter, revision);
        }
        return new MessagePage(withReactions(user, id, page), more, revision, changed, removed, revision < latestRevision);
    }
    @Transactional(readOnly = true)
    public MessagePage history(String username, UUID id, Long before, Long after) {
        return history(username, id, before, after, null);
    }
    public Message send(String username, UUID id, UUID clientId, String text) {
        return send(username, id, clientId, text, null);
    }
    /** Adds reaction totals and every attached file to messages loaded from the database. */
    private List<Message> withReactions(UUID user, UUID conversation, List<Message> messages) {
        var reactions = chats.findReactions(user, messages.stream().map(Message::id).toList());
        var attachments = files.findAttachments(messages.stream().filter(m -> m.attachment() != null).map(Message::id).toList());
        // Quotes of messages from before the user joined show no content.
        long historyFrom = chats.findPositions(user, conversation).historyFrom();
        var people = mentionedPeople(messages);
        return messages.stream().map(m -> m.withReactions(reactions.getOrDefault(m.id(), List.of()))
                .withAttachments(attachments.get(m.id()))
                .quotingFrom(historyFrom).withMentions(mentionsIn(m.text(), people))).toList();
    }
    /** Usernames a text may mention; a trailing full stop or dash is also tried without it ("@bob." is @bob). */
    private static Set<String> mentionCandidates(String text) {
        var found = new LinkedHashSet<String>();
        var matcher = MENTION.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String token = matcher.group(1).toLowerCase(Locale.ROOT);
            found.add(token);
            found.add(token.replaceAll("[.\\-]+$", ""));
        }
        return found;
    }
    /** Looks up, in one query, everyone the messages might mention, including people who have left. */
    private Map<String, Person> mentionedPeople(List<Message> messages) {
        var wanted = new LinkedHashSet<String>();
        for (Message m : messages) wanted.addAll(mentionCandidates(m.text()));
        wanted.removeIf(name -> name.isEmpty() || name.length() > 64);
        var people = new HashMap<String, Person>();
        for (Person p : directory.findAnyByUsernames(wanted)) people.put(p.username(), p);
        return people;
    }
    private static List<Mention> mentionsIn(String text, Map<String, Person> people) {
        var mentioned = new LinkedHashMap<String, Mention>();
        for (String name : mentionCandidates(text)) {
            Person p = people.get(name);
            if (p != null) mentioned.putIfAbsent(name, new Mention(p.id(), p.username(), p.name()));
        }
        return List.copyOf(mentioned.values());
    }
    /** Stores a message once per client ID and updates conversation recency. */
    public Message send(String username, UUID id, UUID clientId, String text, UUID replyTo) {
        UUID user = actor(username);
        member(user, id);
        String body = text.strip();
        if (body.isBlank() || body.length() > 4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must contain 1–4000 characters.");
        return store(user, id, clientId, body, List.of(), replyTo, false).message();
    }
    /** Accepts a quoted message only from the same conversation. */
    private UUID replyTarget(UUID user, UUID conversation, UUID replyTo) {
        if (replyTo == null) return null;
        var found = chats.findMessageRef(user, replyTo);
        if (found.isEmpty() || !found.getFirst().conversation().equals(conversation))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The quoted message is not in this conversation.");
        return replyTo;
    }
    /** Finds a message the caller can act on, or 404. */
    private ChatRepository.MessageRef visibleMessage(UUID user, UUID messageId) {
        var found = chats.findMessageRef(user, messageId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found.");
        return found.getFirst();
    }
    /** The first read only locates the lock. Only the second read may authorize a mutation. */
    private ChatRepository.MessageRef lockedVisibleMessage(UUID user, UUID messageId) {
        var location = visibleMessage(user, messageId);
        chats.lockConversation(location.conversation());
        return visibleMessage(user, messageId);
    }
    private void requireOwnLiveMessage(UUID user, ChatRepository.MessageRef message) {
        if (!message.sender().equals(user))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only change your own messages.");
        if (message.deleted()) throw new ResponseStatusException(HttpStatus.GONE, "This message was deleted.");
    }
    /** Replaces the text of the caller's own message; a file message may have an empty caption. */
    public Message edit(String username, UUID messageId, String text) {
        UUID user = actor(username);
        var message = lockedVisibleMessage(user, messageId);
        requireOwnLiveMessage(user, message);
        String body = text.strip();
        if (body.length() > 4000 || (body.isEmpty() && message.fileId() == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must contain 1–4000 characters.");
        if (!body.equals(message.body()))
            chats.editMessage(messageId, body, Instant.now(), chats.nextRevision(message.conversation()));
        changed(message.conversation());
        return withReactions(user, message.conversation(), List.of(chats.findMessage(messageId))).getFirst();
    }
    /** Deletes the caller's own message for everyone, or any visible message for the caller only. */
    public void delete(String username, UUID messageId, boolean everyone) {
        UUID user = actor(username);
        var message = lockedVisibleMessage(user, messageId);
        long revision = chats.nextRevision(message.conversation());
        if (everyone) {
            requireOwnLiveMessage(user, message);
            Instant now = Instant.now();
            chats.deleteMessage(messageId, now, revision);
            if (message.fileId() != null) {
                // Expired now, so they cannot be downloaded; the bytes are deleted right after this commits,
                // and the hourly retention job remains the fallback if that immediate delete fails.
                for (UUID fileId : files.findIdsForMessage(messageId)) {
                    files.expire(fileId, now);
                    events.publishEvent(new FileUnsent(fileId));
                }
            }
            changed(message.conversation());
        } else {
            chats.hideMessage(user, messageId, revision);
            events.publishEvent(new ChatChanged(List.of(username)));
        }
    }
    /** Sets the caller's reaction to a message, or removes it when emoji is null. */
    public Message react(String username, UUID messageId, String emoji) {
        UUID user = actor(username);
        var message = lockedVisibleMessage(user, messageId);
        if (emoji != null && !REACTIONS.contains(emoji))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose one of the offered reactions.");
        if (message.deleted()) throw new ResponseStatusException(HttpStatus.GONE, "This message was deleted.");
        chats.setReaction(user, messageId, emoji, Instant.now(), chats.nextRevision(message.conversation()));
        changed(message.conversation());
        return withReactions(user, message.conversation(), List.of(chats.findMessage(messageId))).getFirst();
    }
    /** Returns what a message carries so it can be forwarded; expired files cannot be. */
    @Transactional(readOnly = true)
    public ForwardSource forwardSource(String username, UUID messageId) {
        UUID user = actor(username);
        var message = visibleMessage(user, messageId);
        if (message.deleted()) throw new ResponseStatusException(HttpStatus.GONE, "This message was deleted.");
        if (message.fileId() == null) return new ForwardSource(message.body(), List.of());
        var stored = files.findForMessage(messageId);
        for (var file : stored)
            if (file.deletedAt() != null || !file.expiresAt().isAfter(Instant.now()))
                throw new ResponseStatusException(HttpStatus.GONE, "File has expired.");
        return new ForwardSource(message.body(), stored);
    }
    /** Stores a forwarded copy in another conversation the caller belongs to. */
    public Sent sendForward(String username, UUID id, UUID clientId, String text, List<NewFile> files) {
        UUID user = actor(username);
        member(user, id);
        return store(user, id, clientId, text, files, null, true);
    }
    /** Rejects a sender who may not post here before an upload or forwarded copy is written to storage, and
     *  returns the bytes they stored in the last day, for the upload quota. Sending rechecks posting rights. */
    @Transactional(readOnly = true)
    public long checkUpload(String username, UUID conversation) {
        UUID user = actor(username);
        member(user, conversation);
        requirePoster(user, conversation);
        return files.storedSince(user, Instant.now().minus(java.time.Duration.ofDays(1)));
    }
    /** Stores one message carrying already written files, in the order sent, and an optional caption. */
    public Sent sendFiles(String username, UUID id, UUID clientId, String caption, List<NewFile> files, UUID replyTo) {
        UUID user = actor(username);
        member(user, id);
        String body = caption.strip();
        if (body.length() > 4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Caption must contain at most 4000 characters.");
        if (files.isEmpty() || files.size() > MAX_FILES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send between 1 and " + MAX_FILES + " files at a time.");
        return store(user, id, clientId, body, files, replyTo, false);
    }
    /** Inserts a message once per client ID; a retry returns the original if it carries the same content. */
    private Sent store(UUID user, UUID id, UUID clientId, String body, List<NewFile> attached, UUID replyTo,
            boolean forwarded) {
        // 2026-09-23: Lock BEFORE sequence allocation so reconnect/catch-up cannot skip late commits.
        chats.lockConversation(id);
        member(user, id);
        var existing = chats.findRetry(id, user, clientId);
        if (!existing.isEmpty())
            return new Sent(withReactions(user, id, List.of(sameRetry(existing.getFirst(), body, attached))).getFirst(), false);
        requirePoster(user, id);
        replyTo = replyTarget(user, id, replyTo);
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now();
        // The message references its first file; every file then points back to the message.
        var fileIds = new ArrayList<UUID>();
        for (NewFile file : attached) {
            UUID fileId = UUID.randomUUID();
            files.insert(fileId, id, user, file, now, fileIds.size());
            fileIds.add(fileId);
        }
        int inserted = chats.insertMessage(messageId, id, user, clientId, body, now,
                fileIds.isEmpty() ? null : fileIds.getFirst(), replyTo, forwarded);
        if (inserted == 1) {
            files.linkToMessage(messageId, fileIds);
            chats.updateRecency(id, now);
            // A new message returns a removed conversation to every member's list.
            chats.unhide(id, null);
            Message stored = chats.findMessage(messageId);
            notifications.record(stored, mentionCandidates(stored.text()));
            // Replying means the sender has read everything up to their own message.
            chats.markRead(user, id, stored.sequence());
            changed(id);
            return new Sent(withReactions(user, id, List.of(stored)).getFirst(), true);
        }
        fileIds.forEach(files::delete);
        Message retry = chats.findRetry(id, user, clientId).getFirst();
        return new Sent(withReactions(user, id, List.of(sameRetry(retry, body, attached))).getFirst(), false);
    }
    /** Accepts a retry only when its text and files match the message stored for that client ID. */
    private Message sameRetry(Message stored, String body, List<NewFile> attached) {
        boolean same = stored.text().equals(body)
                && attached.stream().map(NewFile::sha256).toList().equals(files.findSha256s(stored.id()));
        if (!same) throw new ResponseStatusException(HttpStatus.CONFLICT, "Retry identifier was already used for different content.");
        return stored;
    }
    /** Finds a file the user may download through conversation membership. */
    @Transactional(readOnly = true)
    public Download download(String username, UUID fileId) {
        UUID user = actor(username);
        var found = files.findForMember(user, fileId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found.");
        var file = found.getFirst();
        if (file.deletedAt() != null || !file.expiresAt().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.GONE, "File has expired.");
        return new Download(file.storageKey(), file.name(), file.contentType(), file.size());
    }

    /** Saves the caller's own pin, mute, remove and manual-unread choices; other members are unaffected. */
    public Conversation preferences(String username, UUID id, Boolean pinned, Boolean muted, Boolean hidden,
            Boolean unread) {
        UUID user = actor(username);
        member(user, id);
        // Removing a conversation also unpins it, as the sidebar does.
        chats.updatePreferences(user, id, Boolean.TRUE.equals(hidden) ? Boolean.FALSE : pinned, muted, hidden, unread);
        events.publishEvent(new ChatChanged(List.of(username)));
        return chats.findConversation(user, id);
    }
    /** Marks messages read up to a sequence, or all of them when sequence is null. */
    public Conversation read(String username, UUID id, Long sequence) {
        UUID user = actor(username);
        member(user, id);
        long before = chats.findPositions(user, id).read();
        chats.markRead(user, id, sequence == null ? Long.MAX_VALUE : sequence);
        long after = chats.findPositions(user, id).read();
        // The caller's other tabs clear the badge; only the senders of the newly read messages get
        // "seen" ticks. Signalling the whole group would make every read in a large group reach everyone.
        var audience = new LinkedHashSet<>(List.of(username));
        if (after > before) audience.addAll(chats.findSenderUsernames(id, before, after, user));
        notify(audience);
        return chats.findConversation(user, id);
    }
    @Transactional(readOnly = true)
    public Presence presence(String username) {
        return directory.findPresence(actor(username));
    }
    /** Saves the caller's status and note and refreshes everyone who can see them. */
    public Presence updatePresence(String username, String status, String note) {
        UUID user = actor(username);
        var presence = new Presence(status, note.strip());
        directory.updatePresence(user, presence);
        var audience = new LinkedHashSet<>(directory.findContactUsernames(user));
        audience.add(username);
        events.publishEvent(new ChatChanged(List.copyOf(audience)));
        return presence;
    }

    public void typing(String username, UUID conversation, boolean active) {
        UUID user = actor(username);
        member(user, conversation);
        events.publishEvent(new ChatTyping(chats.findTypingRecipients(conversation, user), conversation, active));
    }

    /** Sends refresh hints to specific people after the transaction commits. */
    private void notify(Collection<String> usernames) {
        if (!usernames.isEmpty()) events.publishEvent(new ChatChanged(List.copyOf(usernames)));
    }
    /** 2026-09-23: Resolve recipients from membership, then notify only after the transaction commits. */
    private void changed(UUID conversation) {
        events.publishEvent(new ChatChanged(chats.findMemberUsernames(conversation)));
    }
}
