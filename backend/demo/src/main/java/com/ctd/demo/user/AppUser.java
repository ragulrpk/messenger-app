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
    /** 2026-09-23: The existing, canonical ragul account is the sole administrator. */
    public boolean isAdministrator() { return "ragul".equals(username); }
    /** Returns the name shown to other users. */
    public String getDisplayName() { return displayName; }
    /** Returns the stored password hash for authentication checks. */
    public String getPasswordHash() { return passwordHash; }
    /** 2026-09-23: Stores only an encoded replacement after current-password verification. */
    public void changePasswordHash(String encodedPassword) { this.passwordHash = encodedPassword; }
    /** Reports whether this account can sign in and be contacted. */
    public boolean isEnabled() { return enabled; }
}
