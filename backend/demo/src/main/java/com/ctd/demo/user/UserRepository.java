package com.ctd.demo.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/** Provides database access to user accounts for login and messaging. */
public interface UserRepository extends JpaRepository<AppUser, UUID> {
    /** Finds an account by its normalized username. */
    Optional<AppUser> findByUsername(String username);

    /** 2026-09-23: Serialize authentication and password changes for the same account. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.username = :username")
    Optional<AppUser> lockByUsername(@Param("username") String username);
}
