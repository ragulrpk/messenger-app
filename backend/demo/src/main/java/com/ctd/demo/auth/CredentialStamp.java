package com.ctd.demo.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 2026-09-23: Bind sessions to the credential used at login without keeping a password/hash in the stamp. */
public final class CredentialStamp {
    public static final String ATTRIBUTE = "messenger.credentialStamp";
    private CredentialStamp() { }

    public static String of(String passwordHash) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(passwordHash.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static boolean matches(Object stamp, String passwordHash) {
        return stamp instanceof String value && MessageDigest.isEqual(
                value.getBytes(StandardCharsets.UTF_8), of(passwordHash).getBytes(StandardCharsets.UTF_8));
    }
}
