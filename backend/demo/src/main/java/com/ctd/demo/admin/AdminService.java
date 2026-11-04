package com.ctd.demo.admin;

import com.ctd.demo.auth.CredentialStamp;
import com.ctd.demo.auth.LoginOrchestrationService;
import com.ctd.demo.auth.SessionRevocationService;
import com.ctd.demo.auth.SignInEvents;
import com.ctd.demo.chat.ChatChanged;
import com.ctd.demo.chat.ChatRepository;
import com.ctd.demo.chat.ChatService;
import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.EmployeeFields;
import com.ctd.demo.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.Reader;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspace administration, as in Slack or Teams: people, groups and an activity record.
 *
 *  <p>Only an enabled administrator whose session came through the administrator sign-in (password and emailed
 *  code) may act; every action is recorded in account_audit. An administrator never learns a working password:
 *  new accounts get a temporary one that only starts a first sign-in confirmed by a code sent to the employee's
 *  own email, and "require password reset" sets one nobody knows. */
@Service
public class AdminService {
    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final int PAGE = 50;
    private static final int GROUP_LIMIT = 100;
    private static final int BULK_LIMIT = 1000;
    // No 0/O, 1/l/I: temporary passwords are read out or typed by hand.
    private static final String TEMPORARY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SessionRevocationService sessions;
    private final SignInEvents signIns;
    private final ChatRepository chats;
    private final EmployeeImport importer;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate db;

    public AdminService(UserRepository users, PasswordEncoder encoder, SessionRevocationService sessions,
            SignInEvents signIns, ChatRepository chats, EmployeeImport importer, ApplicationEventPublisher events,
            JdbcTemplate db) {
        this.users = users;
        this.encoder = encoder;
        this.sessions = sessions;
        this.signIns = signIns;
        this.chats = chats;
        this.importer = importer;
        this.events = events;
        this.db = db;
    }

    public record Person(UUID id, String loginId, String gpfNumber, String name, String email, String mobile, String cadre,
                         boolean enabled, boolean administrator, boolean mustChangePassword, Instant lastLoginAt) { }
    public record PersonDetail(Person person, List<SignInEvents.Event> signIns) { }
    public record Page<T>(List<T> items, boolean hasMore) { }
    /** Login ID and gpf: one of them; the GPF number, lower-cased, becomes the login ID. */
    public record NewPerson(String gpfNumber, String username, String name, String email, String mobile, String cadre) { }
    public record Details(String name, String email, String mobile, String cadre) { }
    /** A temporary password for the administrator to hand over; never logged. */
    public record Issued(String loginId, String temporaryPassword) {
        @Override public String toString() { return "Issued[" + loginId + ", REDACTED]"; }
    }
    public record GroupDetail(ChatRepository.GroupSummary group, List<ChatService.Member> members) { }
    public record NewGroup(String name, List<UUID> memberIds, List<UUID> adminIds, boolean adminsOnly) { }
    public record AuditEntry(Instant occurredAt, String actor, String target, String action, String detail) { }

    // ---- People --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<Person> people(String username, HttpSession session, String query, int offset) {
        administrator(username, session);
        String pattern = like(query);
        var found = db.query(PEOPLE + "WHERE username LIKE ? ESCAPE '!' OR lower(display_name) LIKE ? ESCAPE '!' "
                + "OR lower(coalesce(email,'')) LIKE ? ESCAPE '!' OR coalesce(mobile,'') LIKE ? ESCAPE '!' "
                + "ORDER BY lower(display_name),username LIMIT ? OFFSET ?", AdminService::person,
                pattern, pattern, pattern, pattern, PAGE + 1, Math.max(0, offset));
        return new Page<>(List.copyOf(found.subList(0, Math.min(PAGE, found.size()))), found.size() > PAGE);
    }

    @Transactional(readOnly = true)
    public PersonDetail person(String username, HttpSession session, UUID id) {
        administrator(username, session);
        var person = db.query(PEOPLE + "WHERE id=?", AdminService::person, id).stream().findFirst()
                .orElseThrow(AdminService::notFound);
        return new PersonDetail(person, signIns.forUser(id, 20));
    }

    /** Creates an employee with a temporary password, shown once. Their first sign-in asks for a code sent to
     *  their email and a new password, so the administrator cannot use it. */
    @Transactional
    public Issued create(String username, HttpSession session, NewPerson body) {
        var admin = administrator(username, session);
        String gpf, loginId, email, mobile, name, cadre;
        try {
            gpf = blank(body.gpfNumber()) ? null : EmployeeFields.gpf(body.gpfNumber());
            loginId = gpf != null ? gpf.toLowerCase(Locale.ROOT) : EmployeeFields.username(body.username());
            name = EmployeeFields.name(body.name());
            email = EmployeeFields.email(body.email());
            mobile = EmployeeFields.mobile(body.mobile());
            cadre = EmployeeFields.cadre(body.cadre());
        } catch (IllegalArgumentException exception) {
            throw invalid(exception);
        }
        if (users.findByUsername(loginId).isPresent()) throw conflict("That GPF number or username is already in use.");
        if (gpf != null && users.findByGpfNumber(gpf).isPresent()) throw conflict("That GPF number is already in use.");
        requireUnique(email, mobile, null);
        String temporary = temporaryPassword();
        var account = new AppUser(loginId, name, encoder.encode(temporary));
        account.setDetails(gpf, email, mobile, cadre);
        account.requirePasswordChange();
        // Flushed now: the audit row written below over JDBC references the new account.
        users.saveAndFlush(account);
        audit(admin, account, "ACCOUNT_CREATED", null);
        return new Issued(loginId, temporary);
    }

    @Transactional
    public Person update(String username, HttpSession session, UUID id, Details body) {
        var admin = administrator(username, session);
        var account = users.lockById(id).orElseThrow(AdminService::notFound);
        String name, email, mobile, cadre;
        try {
            name = EmployeeFields.name(body.name());
            email = EmployeeFields.email(body.email());
            mobile = EmployeeFields.mobile(body.mobile());
            cadre = EmployeeFields.cadre(body.cadre());
        } catch (IllegalArgumentException exception) {
            throw invalid(exception);
        }
        requireUnique(email, mobile, id);
        account.rename(name);
        account.setDetails(account.getGpfNumber(), email, mobile, cadre);
        audit(admin, account, "ACCOUNT_UPDATED", null);
        return view(account);
    }

    /** Disabling signs the person out everywhere at once; their messages stay. */
    @Transactional
    public Person setEnabled(String username, HttpSession session, UUID id, boolean enabled) {
        var admin = lockedAdministrator(username, session);
        var account = other(admin, id, "You cannot disable your own account.");
        account.setEnabled(enabled);
        if (!enabled) sessions.revokeAll(account.getUsername());
        audit(admin, account, enabled ? "ACCOUNT_ENABLED" : "ACCOUNT_DISABLED", null);
        return view(account);
    }

    /** Grants or revokes administration. Administrators sign in with an emailed code, so they need an email
     *  address; an enabled administrator always remains. */
    @Transactional
    public Person setAdministrator(String username, HttpSession session, UUID id, boolean administrator) {
        var admin = lockedAdministrator(username, session);
        var account = users.lockById(id).orElseThrow(AdminService::notFound);
        if (administrator && account.getEmail() == null)
            throw new AdminRejected(HttpStatus.BAD_REQUEST, "NO_EMAIL", "Add an email address first: administrators sign in with a code sent to it.");
        if (!administrator && account.isAdministrator() && account.isEnabled() && enabledAdministrators() <= 1)
            throw new AdminRejected(HttpStatus.BAD_REQUEST, "LAST_ADMINISTRATOR", "Make someone else an administrator first.");
        account.setAdministrator(administrator);
        // The role decides which sign-in page works, so the current session ends.
        sessions.revokeAll(account.getUsername());
        audit(admin, account, administrator ? "ADMINISTRATOR_GRANTED" : "ADMINISTRATOR_REVOKED", null);
        return view(account);
    }

    @Transactional
    public Person signOut(String username, HttpSession session, UUID id) {
        var admin = administrator(username, session);
        var account = other(admin, id, "Use Sign out to end your own session.");
        sessions.revokeAll(account.getUsername());
        audit(admin, account, "SIGNED_OUT", null);
        return view(account);
    }

    /** Replaces the password with one nobody knows and signs the person out: they set a new one with
     *  "Forgot password", which sends a code to their own email. */
    @Transactional
    public Person requirePasswordReset(String username, HttpSession session, UUID id) {
        var admin = administrator(username, session);
        var account = other(admin, id, "Use Change password for your own account.");
        account.issueTemporaryPassword(encoder.encode(temporaryPassword() + temporaryPassword()), Instant.now());
        sessions.revokeAll(account.getUsername());
        audit(admin, account, "PASSWORD_RESET_REQUIRED", null);
        return view(account);
    }

    /** Adds up to 1000 people from a CSV file without passwords; they set theirs with "Forgot password". */
    public EmployeeImport.Report bulkAdd(String username, HttpSession session, Reader csv) {
        var admin = administrator(username, session);
        try {
            return importer.importCsv(csv, false, admin.getId(), BULK_LIMIT);
        } catch (IllegalArgumentException exception) {
            throw invalid(exception);
        } catch (IOException exception) {
            throw new AdminRejected(HttpStatus.BAD_REQUEST, "INVALID_FILE", "The file could not be read. Save it as CSV UTF-8.");
        }
    }

    // ---- Groups --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<ChatRepository.GroupSummary> groups(String username, HttpSession session, String query, int offset) {
        administrator(username, session);
        String term = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        var found = chats.findGroups(term, Math.max(0, offset), PAGE + 1);
        return new Page<>(List.copyOf(found.subList(0, Math.min(PAGE, found.size()))), found.size() > PAGE);
    }

    @Transactional(readOnly = true)
    public GroupDetail group(String username, HttpSession session, UUID id) {
        administrator(username, session);
        return detail(id);
    }

    /** Creates a group for others; the administrator is not added. At least one member is a group admin. */
    @Transactional
    public GroupDetail createGroup(String username, HttpSession session, NewGroup body) {
        var admin = administrator(username, session);
        String name = groupName(body.name());
        var members = new LinkedHashSet<>(body.memberIds() == null ? List.<UUID>of() : body.memberIds());
        var admins = new LinkedHashSet<>(body.adminIds() == null ? List.<UUID>of() : body.adminIds());
        if (members.size() < 2 || members.size() > GROUP_LIMIT)
            throw new AdminRejected(HttpStatus.BAD_REQUEST, "INVALID_MEMBERS", "Choose between 2 and " + GROUP_LIMIT + " people.");
        if (admins.isEmpty() || !members.containsAll(admins))
            throw new AdminRejected(HttpStatus.BAD_REQUEST, "INVALID_ADMINS", "Choose at least one group admin among the members.");
        members.forEach(this::requireEnabled);
        UUID owner = admins.iterator().next();
        var others = new LinkedHashSet<>(members);
        others.remove(owner);
        UUID id = UUID.randomUUID();
        chats.createGroup(id, name, owner, others, body.adminsOnly(), Instant.now());
        for (UUID person : admins) chats.setRole(id, person, "admin");
        changed(id, null);
        audit(admin, null, "GROUP_CREATED", name);
        return detail(id);
    }

    @Transactional
    public GroupDetail addGroupMembers(String username, HttpSession session, UUID id, List<UUID> userIds) {
        var admin = administrator(username, session);
        var group = lockedGroup(id);
        var current = chats.findMembers(id).stream().map(ChatService.Member::id).toList();
        var added = new LinkedHashSet<>(userIds == null ? List.<UUID>of() : userIds);
        added.removeAll(current);
        if (current.size() + added.size() > GROUP_LIMIT)
            throw new AdminRejected(HttpStatus.BAD_REQUEST, "GROUP_FULL", "A group can have at most " + GROUP_LIMIT + " members.");
        added.forEach(this::requireEnabled);
        Instant now = Instant.now();
        for (UUID person : added) chats.addMember(id, person, now);
        chats.refreshMemberCount(id);
        changed(id, null);
        audit(admin, null, "GROUP_MEMBERS_ADDED", group.name());
        return detail(id);
    }

    /** Removes a member at once; if they were the last group admin, the longest-standing member takes over. */
    @Transactional
    public GroupDetail removeGroupMember(String username, HttpSession session, UUID id, UUID member) {
        var admin = administrator(username, session);
        var group = lockedGroup(id);
        String removed = memberUsername(id, member);
        chats.removeMember(id, member, Instant.now());
        chats.refreshMemberCount(id);
        if (chats.countAdmins(id) == 0) chats.findEarliestMember(id).ifPresent(next -> chats.setRole(id, next, "admin"));
        changed(id, removed);
        audit(admin, users.findById(member).orElse(null), "GROUP_MEMBER_REMOVED", group.name());
        return detail(id);
    }

    @Transactional
    public GroupDetail setGroupRole(String username, HttpSession session, UUID id, UUID member, String role) {
        var admin = administrator(username, session);
        var group = lockedGroup(id);
        if (!"admin".equals(role) && !"member".equals(role))
            throw new AdminRejected(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Choose admin or member.");
        memberUsername(id, member);
        if ("member".equals(role) && chats.findRole(id, member).filter("admin"::equals).isPresent() && chats.countAdmins(id) == 1)
            throw new AdminRejected(HttpStatus.BAD_REQUEST, "LAST_GROUP_ADMIN", "A group needs at least one admin.");
        chats.setRole(id, member, role);
        changed(id, null);
        audit(admin, users.findById(member).orElse(null), "admin".equals(role) ? "GROUP_ADMIN_GRANTED" : "GROUP_ADMIN_REVOKED", group.name());
        return detail(id);
    }

    @Transactional
    public GroupDetail renameGroup(String username, HttpSession session, UUID id, String name) {
        var admin = administrator(username, session);
        var group = lockedGroup(id);
        String title = groupName(name);
        chats.rename(id, title);
        changed(id, null);
        audit(admin, null, "GROUP_RENAMED", group.name() + " → " + title);
        return detail(id);
    }

    @Transactional
    public GroupDetail setGroupAdminsOnly(String username, HttpSession session, UUID id, boolean adminsOnly) {
        var admin = administrator(username, session);
        var group = lockedGroup(id);
        chats.setAdminsOnly(id, adminsOnly);
        changed(id, null);
        audit(admin, null, adminsOnly ? "GROUP_ANNOUNCEMENTS_ON" : "GROUP_ANNOUNCEMENTS_OFF", group.name());
        return detail(id);
    }

    /** Archived groups keep their history but nobody can post; archiving can be undone. */
    @Transactional
    public GroupDetail setGroupArchived(String username, HttpSession session, UUID id, boolean archived) {
        var admin = administrator(username, session);
        var group = lockedGroup(id);
        chats.setArchived(id, archived ? Instant.now() : null);
        changed(id, null);
        audit(admin, null, archived ? "GROUP_ARCHIVED" : "GROUP_RESTORED", group.name());
        return detail(id);
    }

    // ---- Activity ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AuditEntry> audit(String username, HttpSession session) {
        administrator(username, session);
        return db.query("SELECT a.occurred_at,actor.username AS actor,target.username AS target,a.action,a.detail FROM account_audit a "
                        + "LEFT JOIN app_users actor ON actor.id=a.actor_id LEFT JOIN app_users target ON target.id=a.target_id "
                        + "ORDER BY a.occurred_at DESC LIMIT 200",
                (rs, n) -> new AuditEntry(rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor"),
                        rs.getString("target"), rs.getString("action"), rs.getString("detail")));
    }

    @Transactional(readOnly = true)
    public List<SignInEvents.Event> signIns(String username, HttpSession session) {
        administrator(username, session);
        return signIns.recent(200);
    }

    // ---- Checks --------------------------------------------------------------------------------------

    /** An enabled administrator, whose session is bound to their current password and came through the
     *  administrator sign-in (password and emailed code). */
    private AppUser administrator(String username, HttpSession session) {
        var admin = users.findByUsername(username).filter(u -> u.isEnabled() && u.isAdministrator())
                .orElseThrow(() -> new AdminRejected(HttpStatus.FORBIDDEN, "NOT_ADMINISTRATOR", "Administrator access is required."));
        if (session == null || !CredentialStamp.matches(session.getAttribute(CredentialStamp.ATTRIBUTE), admin.getPasswordHash()))
            throw new AdminRejected(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "Please sign in again.");
        if (!Boolean.TRUE.equals(session.getAttribute(LoginOrchestrationService.ADMIN_SESSION)))
            throw new AdminRejected(HttpStatus.FORBIDDEN, "ADMIN_SIGN_IN_REQUIRED",
                    "Sign in on the administrator sign-in page to use administration.");
        return admin;
    }

    /** Locks every enabled administrator row before a role or account-state change, so two administrators acting
     *  on each other at once are serialized and "one administrator remains" cannot be raced. */
    private AppUser lockedAdministrator(String username, HttpSession session) {
        db.queryForList("SELECT id FROM app_users WHERE administrator=TRUE AND enabled=TRUE ORDER BY id FOR UPDATE", UUID.class);
        return administrator(username, session);
    }

    private int enabledAdministrators() {
        return db.queryForObject("SELECT count(*) FROM app_users WHERE administrator=TRUE AND enabled=TRUE", Integer.class);
    }

    private AppUser other(AppUser admin, UUID id, String selfMessage) {
        var account = users.lockById(id).orElseThrow(AdminService::notFound);
        if (account.getId().equals(admin.getId())) throw new AdminRejected(HttpStatus.BAD_REQUEST, "OWN_ACCOUNT", selfMessage);
        return account;
    }

    private void requireUnique(String email, String mobile, UUID except) {
        if (users.findByEmail(email).filter(u -> !u.getId().equals(except)).isPresent())
            throw conflict("That email address is already used by another account.");
        if (mobile != null && users.findByMobile(mobile).filter(u -> !u.getId().equals(except)).isPresent())
            throw conflict("That mobile number is already used by another account.");
    }

    private void requireEnabled(UUID person) {
        users.findById(person).filter(AppUser::isEnabled).orElseThrow(() ->
                new AdminRejected(HttpStatus.BAD_REQUEST, "INVALID_MEMBERS", "Everyone chosen must have an active account."));
    }

    private ChatRepository.GroupSummary lockedGroup(UUID id) {
        var group = chats.findGroup(id).orElseThrow(() ->
                new AdminRejected(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "That group no longer exists."));
        chats.lockConversation(id);
        return group;
    }

    private String memberUsername(UUID group, UUID member) {
        return chats.findMembers(group).stream().filter(m -> m.id().equals(member)).map(ChatService.Member::username)
                .findFirst().orElseThrow(() -> new AdminRejected(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "That person is not in the group."));
    }

    private GroupDetail detail(UUID id) {
        var group = chats.findGroup(id).orElseThrow(() ->
                new AdminRejected(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "That group no longer exists."));
        return new GroupDetail(group, chats.findMembers(id));
    }

    /** Refreshes the members' (and a removed member's) apps after the change commits. */
    private void changed(UUID group, String formerMember) {
        var audience = new LinkedHashSet<>(chats.findMemberUsernames(group));
        if (formerMember != null) audience.add(formerMember);
        events.publishEvent(new ChatChanged(List.copyOf(audience)));
    }

    private void audit(AppUser actor, AppUser target, String action, String detail) {
        db.update("INSERT INTO account_audit(id,occurred_at,actor_id,target_id,action,detail) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), Timestamp.from(Instant.now()), actor.getId(), target == null ? null : target.getId(),
                action, detail == null || detail.length() <= 200 ? detail : detail.substring(0, 200));
        log.info("Administrative action action={} actor={} target={}", action, actor.getUsername(),
                target == null ? null : target.getUsername());
    }

    private static final String PEOPLE = "SELECT id,username,gpf_number,display_name,email,mobile,cadre,enabled,administrator,"
            + "must_change_password,last_login_at FROM app_users ";

    private static Person person(ResultSet rs, int row) throws SQLException {
        var last = rs.getTimestamp("last_login_at");
        return new Person(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("gpf_number"),
                rs.getString("display_name"), rs.getString("email"), rs.getString("mobile"), rs.getString("cadre"),
                rs.getBoolean("enabled"), rs.getBoolean("administrator"), rs.getBoolean("must_change_password"),
                last == null ? null : last.toInstant());
    }

    private static Person view(AppUser user) {
        return new Person(user.getId(), user.getUsername(), user.getGpfNumber(), user.getDisplayName(), user.getEmail(),
                user.getMobile(), user.getCadre(), user.isEnabled(), user.isAdministrator(), user.isMustChangePassword(),
                user.getLastLoginAt());
    }

    private static String like(String query) {
        String term = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (term.length() > 100) throw new AdminRejected(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Search is too long.");
        return "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String groupName(String value) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty() || name.length() > 100)
            throw new AdminRejected(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Group name must be 1–100 characters.");
        return name;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    static String temporaryPassword() {
        var password = new StringBuilder();
        for (int i = 0; i < 16; i++) password.append(TEMPORARY_ALPHABET.charAt(RANDOM.nextInt(TEMPORARY_ALPHABET.length())));
        return password.toString();
    }

    private static AdminRejected invalid(IllegalArgumentException exception) {
        return new AdminRejected(HttpStatus.BAD_REQUEST, "INVALID_DETAILS", exception.getMessage());
    }

    private static AdminRejected conflict(String message) {
        return new AdminRejected(HttpStatus.CONFLICT, "ALREADY_IN_USE", message);
    }

    private static AdminRejected notFound() {
        return new AdminRejected(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "That account no longer exists.");
    }
}
