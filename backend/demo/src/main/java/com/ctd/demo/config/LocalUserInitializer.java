package com.ctd.demo.config;

import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Optionally creates a configured local account when the application starts. */
@Component
@Profile("local")
public class LocalUserInitializer implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    /** Receives and normalizes optional bootstrap account settings. */
    public LocalUserInitializer(UserRepository users, PasswordEncoder encoder,
            @Value("${app.bootstrap.username:}") String username,
            @Value("${app.bootstrap.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.username = username.trim().toLowerCase(Locale.ROOT);
        this.password = password;
    }

    /** Validates bootstrap credentials and creates the account if absent. */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() && password.isBlank()) return;
        if (!username.matches("[a-z0-9._-]{3,64}") || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Set a 3-64 character bootstrap username and a 12-character minimum password (72 UTF-8 bytes maximum).");
        }
        if (users.findByUsername(username).isEmpty()) {
            users.save(new AppUser(username, username, encoder.encode(password)));
        }
    }
}
