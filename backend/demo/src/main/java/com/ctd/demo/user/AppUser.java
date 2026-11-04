package com.ctd.demo.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Maps a user account and its stored password hash to the app_users table. */
@Entity
@Table(name = "app_users")
public class AppUser {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 64)
    private String username;
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private boolean administrator;
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;
    @Column(name = "password_reset_at")
    private Instant passwordResetAt;
    @Column(name = "gpf_number", length = 32)
    private String gpfNumber;
    @Column(length = 254)
    private String email;
    @Column(length = 10)
    private String mobile;
    @Column(length = 100)
    private String cadre;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Lets JPA reconstruct an account from a database row. */
    protected AppUser() { }

    /** Creates an enabled account with a new ID and creation time. */
    public AppUser(String username, String displayName, String passwordHash) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    /** Returns the account ID. */
    public UUID getId() { return id; }
    /** Returns the login username. */
    public String getUsername() { return username; }
    /** Whether this account may administer accounts; granted and revoked by other administrators. */
    public boolean isAdministrator() { return administrator; }
    public void setAdministrator(boolean administrator) { this.administrator = administrator; }
    /** Returns the name shown to other users. */
    public String getDisplayName() { return displayName; }
    public void rename(String displayName) { this.displayName = displayName; }
    /** Returns the stored password hash for authentication checks. */
    public String getPasswordHash() { return passwordHash; }
    /** The owner chose a new password: any temporary password requirement ends. */
    public void changePasswordHash(String encodedPassword) {
        this.passwordHash = encodedPassword;
        this.mustChangePassword = false;
    }
    /** An administrator issued a temporary password: it only allows choosing a new one, and the owner is told. */
    public void issueTemporaryPassword(String encodedPassword, Instant issuedAt) {
        this.passwordHash = encodedPassword;
        this.mustChangePassword = true;
        this.passwordResetAt = issuedAt;
    }
    /** A new account starts with a temporary password; unlike a reset, there is nothing to tell the owner. */
    public void requirePasswordChange() { this.mustChangePassword = true; }
    /** Whether the current password is temporary and must be replaced before anything else. */
    public boolean isMustChangePassword() { return mustChangePassword; }
    /** When an administrator last reset this account's password, or null. */
    public Instant getPasswordResetAt() { return passwordResetAt; }
    /** Reports whether this account can sign in and be contacted. */
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** The employee's GPF number as issued (their login ID, lower-cased, is the username), or null. */
    public String getGpfNumber() { return gpfNumber; }
    /** Where one-time codes are sent; normalized lower-case. */
    public String getEmail() { return email; }
    /** Ten-digit mobile number, or null. */
    public String getMobile() { return mobile; }
    public String getCadre() { return cadre; }
    /** Employee details kept in this database; uniqueness is checked by the caller and the schema. */
    public void setDetails(String gpfNumber, String email, String mobile, String cadre) {
        this.gpfNumber = gpfNumber;
        this.email = email;
        this.mobile = mobile;
        this.cadre = cadre;
    }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void signedIn(Instant at) { this.lastLoginAt = at; }
}
