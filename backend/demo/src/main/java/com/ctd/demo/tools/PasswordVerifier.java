package com.ctd.demo.tools;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 2026-09-23: Standalone password checker for the application's BCrypt hashes.
 * BCrypt is one-way: this verifies a supplied password; it cannot decode a hash.
 * Run main in your IDE or terminal. No database connection or Spring startup is needed.
 */
public final class PasswordVerifier {
    private PasswordVerifier() { }

    /** Prompts for a stored hash and candidate password, then prints only the match result. */
    public static void main(String[] args) throws IOException {
        Console console = System.console();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println("BCrypt verifies passwords; it does not decode them.");
        System.out.print("Paste the stored BCrypt hash: ");
        System.out.flush();
        String hash = console != null ? console.readLine() : input.readLine();
        if (hash == null) return;

        char[] password;
        if (console != null) {
            password = console.readPassword("Password to check (hidden): ");
        } else {
            // IDE consoles usually lack java.io.Console and cannot hide typed input.
            System.out.print("Password to check (input is visible in this console): ");
            System.out.flush();
            String line = input.readLine();
            password = line == null ? null : line.toCharArray();
        }
        if (password == null) return;
        try {
            // Do not trim the password: spaces can be part of the original credential.
            boolean matches = matches(new String(password), hash.trim());
            System.out.println(matches ? "MATCH: the password is correct." : "NO MATCH: the password is incorrect.");
        } catch (IllegalArgumentException exception) {
            System.out.println(exception.getMessage());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Applies the same UTF-8 password length ceiling used by application authentication. */
    public static boolean matches(String password, String hash) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Password must not exceed 72 UTF-8 bytes.");
        if (hash == null || !hash.matches("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}"))
            throw new IllegalArgumentException("Invalid BCrypt hash. Paste the complete stored password_hash value.");
        return new BCryptPasswordEncoder().matches(password, hash);
    }
}
