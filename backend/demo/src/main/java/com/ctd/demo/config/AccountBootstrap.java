package com.ctd.demo.config;

import com.ctd.demo.user.AppUser;
import com.ctd.demo.user.EmployeeFields;
import com.ctd.demo.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the first administrator on a fresh installation from BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD and
 *  BOOTSTRAP_EMAIL (administrators sign in with a code sent to that address). The password is temporary: the
 *  first sign-in requires the emailed code and a new password, so the value left in the environment stops
 *  working. An existing account is never changed, except that a missing email address is filled in. */
@Component
public class AccountBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountBootstrap.class);
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;
    private final String email;

    public AccountBootstrap(UserRepository users, PasswordEncoder encoder,
            @Value("${app.bootstrap.username:}") String username,
            @Value("${app.bootstrap.password:}") String password,
            @Value("${app.bootstrap.email:}") String email) {
        this.users = users;
        this.encoder = encoder;
        this.username = username.trim().toLowerCase(Locale.ROOT);
        this.password = password;
        this.email = email.isBlank() ? null : EmployeeFields.email(email);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() && password.isBlank()) return;
        if (!username.matches("[a-z0-9._-]{3,64}") || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Set a 3-64 character bootstrap username and a 12-character minimum password (72 UTF-8 bytes maximum).");
        }
        var existing = users.findByUsername(username);
        if (existing.isPresent()) {
            var account = existing.get();
            if (account.getEmail() == null && email != null && users.findByEmail(email).isEmpty()) {
                account.setDetails(account.getGpfNumber(), email, account.getMobile(), account.getCadre());
                log.info("Bootstrap account email address added username={}", username);
            }
            return;
        }
        if (email == null) throw new IllegalStateException("Set BOOTSTRAP_EMAIL: administrators sign in with a code sent to it.");
        var account = new AppUser(username, username, encoder.encode(password));
        account.setDetails(null, email, null, null);
        account.setAdministrator(true);
        account.requirePasswordChange();
        users.save(account);
        log.info("Bootstrap administrator account created username={}", username);
    }
}
