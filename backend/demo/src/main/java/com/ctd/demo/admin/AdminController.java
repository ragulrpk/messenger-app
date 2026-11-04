package com.ctd.demo.admin;

import com.ctd.demo.auth.SignInEvents;
import com.ctd.demo.chat.ChatRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Session- and CSRF-protected workspace administration; the service checks for an administrator who signed in
 *  through the administrator page on every call. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService admin;
    public AdminController(AdminService admin) { this.admin = admin; }

    public record EnabledRequest(@NotNull Boolean enabled) { }
    public record AdministratorRequest(@NotNull Boolean administrator) { }
    public record MembersRequest(List<UUID> userIds) { }
    public record RoleRequest(String role) { }
    public record NameRequest(String name) { }
    public record AdminsOnlyRequest(@NotNull Boolean adminsOnly) { }
    public record ArchivedRequest(@NotNull Boolean archived) { }

    // ---- People --------------------------------------------------------------------------------------

    @GetMapping("/people")
    public AdminService.Page<AdminService.Person> people(Authentication auth, HttpServletRequest request,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int offset) {
        return admin.people(auth.getName(), request.getSession(false), q, offset);
    }
    @GetMapping("/people/{id}")
    public AdminService.PersonDetail person(Authentication auth, HttpServletRequest request, @PathVariable UUID id) {
        return admin.person(auth.getName(), request.getSession(false), id);
    }
    /** Returns the new account's temporary password once; it is not stored readably or logged. */
    @PostMapping("/people")
    public AdminService.Issued create(Authentication auth, HttpServletRequest request, @RequestBody AdminService.NewPerson body) {
        return admin.create(auth.getName(), request.getSession(false), body);
    }
    @PostMapping("/people/{id}")
    public AdminService.Person update(Authentication auth, HttpServletRequest request, @PathVariable UUID id,
            @RequestBody AdminService.Details body) {
        return admin.update(auth.getName(), request.getSession(false), id, body);
    }
    @PostMapping("/people/{id}/enabled")
    public AdminService.Person enabled(Authentication auth, HttpServletRequest request, @PathVariable UUID id,
            @Valid @RequestBody EnabledRequest body) {
        return admin.setEnabled(auth.getName(), request.getSession(false), id, body.enabled());
    }
    @PostMapping("/people/{id}/administrator")
    public AdminService.Person administrator(Authentication auth, HttpServletRequest request, @PathVariable UUID id,
            @Valid @RequestBody AdministratorRequest body) {
        return admin.setAdministrator(auth.getName(), request.getSession(false), id, body.administrator());
    }
    @PostMapping("/people/{id}/sign-out")
    public AdminService.Person signOut(Authentication auth, HttpServletRequest request, @PathVariable UUID id) {
        return admin.signOut(auth.getName(), request.getSession(false), id);
    }
    @PostMapping("/people/{id}/password-reset")
    public AdminService.Person passwordReset(Authentication auth, HttpServletRequest request, @PathVariable UUID id) {
        return admin.requirePasswordReset(auth.getName(), request.getSession(false), id);
    }
    /** Adds people from a CSV file (no password column); returns how many were added and which lines were skipped. */
    @PostMapping(path = "/people/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeImport.Report bulk(Authentication auth, HttpServletRequest request, @RequestPart MultipartFile file) {
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            return admin.bulkAdd(auth.getName(), request.getSession(false), reader);
        } catch (IOException exception) {
            throw new AdminRejected(HttpStatus.BAD_REQUEST, "INVALID_FILE", "The file could not be read. Save it as CSV UTF-8.");
        }
    }

    // ---- Groups --------------------------------------------------------------------------------------

    @GetMapping("/groups")
    public AdminService.Page<ChatRepository.GroupSummary> groups(Authentication auth, HttpServletRequest request,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int offset) {
        return admin.groups(auth.getName(), request.getSession(false), q, offset);
    }
    @GetMapping("/groups/{id}")
    public AdminService.GroupDetail group(Authentication auth, HttpServletRequest request, @PathVariable UUID id) {
        return admin.group(auth.getName(), request.getSession(false), id);
    }
    @PostMapping("/groups")
    public AdminService.GroupDetail createGroup(Authentication auth, HttpServletRequest request,
            @RequestBody AdminService.NewGroup body) {
        return admin.createGroup(auth.getName(), request.getSession(false), body);
    }
    @PostMapping("/groups/{id}/members")
    public AdminService.GroupDetail addMembers(Authentication auth, HttpServletRequest request, @PathVariable UUID id,
            @RequestBody MembersRequest body) {
        return admin.addGroupMembers(auth.getName(), request.getSession(false), id, body.userIds());
    }
    @PostMapping("/groups/{id}/members/{userId}/remove")
    public AdminService.GroupDetail removeMember(Authentication auth, HttpServletRequest request, @PathVariable UUID id,
            @PathVariable UUID userId) {
        return admin.removeGroupMember(auth.getName(), request.getSession(false), id, userId);
    }
    @PostMapping("/groups/{id}/members/{userId}/role")
    public AdminService.GroupDetail role(Authentication auth, HttpServletRequest request, @PathVariable UUID id,
            @PathVariable UUID userId, @RequestBody RoleRequest body) {
        return admin.setGroupRole(auth.getName(), request.getSession(false), id, userId, body.role());
    }
    @PostMapping("/groups/{id}/rename")
    public AdminService.GroupDetail rename(Authentication auth, HttpServletRequest request, @PathVariable UUID id,
            @RequestBody NameRequest body) {
        return admin.renameGroup(auth.getName(), request.getSession(false), id, body.name());
    }
    @PostMapping("/groups/{id}/settings")
    public AdminService.GroupDetail settings(Authentication auth, HttpServletRequest request, @PathVariable UUID id,
            @Valid @RequestBody AdminsOnlyRequest body) {
        return admin.setGroupAdminsOnly(auth.getName(), request.getSession(false), id, body.adminsOnly());
    }
    @PostMapping("/groups/{id}/archive")
    public AdminService.GroupDetail archive(Authentication auth, HttpServletRequest request, @PathVariable UUID id,
            @Valid @RequestBody ArchivedRequest body) {
        return admin.setGroupArchived(auth.getName(), request.getSession(false), id, body.archived());
    }

    // ---- Activity ------------------------------------------------------------------------------------

    @GetMapping("/audit")
    public List<AdminService.AuditEntry> audit(Authentication auth, HttpServletRequest request) {
        return admin.audit(auth.getName(), request.getSession(false));
    }
    @GetMapping("/sign-ins")
    public List<SignInEvents.Event> signIns(Authentication auth, HttpServletRequest request) {
        return admin.signIns(auth.getName(), request.getSession(false));
    }
}
