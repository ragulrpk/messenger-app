package com.ctd.demo.user;

import java.util.Locale;
import java.util.regex.Pattern;

/** Normalizes and validates employee details the same way everywhere: administrator console, bulk add, the
 *  server-side import and the bootstrap account. Each method returns the stored form or throws
 *  IllegalArgumentException with a message fit to show an administrator. */
public final class EmployeeFields {
    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9._-]{3,64}");
    private static final Pattern GPF = Pattern.compile("[A-Z0-9._-]{3,32}");
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+'-]+@[a-z0-9-]+(\\.[a-z0-9-]+)+");
    private EmployeeFields() { }

    /** A GPF number as issued, upper-cased; its lower-case form is the login ID. */
    public static String gpf(String value) {
        String gpf = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!GPF.matcher(gpf).matches())
            throw new IllegalArgumentException("GPF number must be 3–32 letters, digits, dots, dashes or underscores.");
        return gpf;
    }

    /** An assigned username for employees without a GPF number. */
    public static String username(String value) {
        String username = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!LOGIN_ID.matcher(username).matches())
            throw new IllegalArgumentException("Username must be 3–64 lowercase letters, digits, dots, dashes or underscores.");
        return username;
    }

    public static String email(String value) {
        String email = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches())
            throw new IllegalArgumentException("Enter a valid email address.");
        return email;
    }

    /** A ten-digit Indian mobile number; spaces, dashes and a +91/91/0 prefix are removed. Blank means none. */
    public static String mobile(String value) {
        String digits = value == null ? "" : value.replaceAll("[\\s()-]", "");
        if (digits.isEmpty()) return null;
        if (digits.startsWith("+")) digits = digits.substring(1);
        if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        if (!digits.matches("[6-9]\\d{9}"))
            throw new IllegalArgumentException("Mobile number must be 10 digits starting with 6, 7, 8 or 9.");
        return digits;
    }

    public static String name(String value) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("Name must be 1–100 characters.");
        return name;
    }

    /** Optional; blank means none. */
    public static String cadre(String value) {
        String cadre = value == null ? "" : value.strip();
        if (cadre.length() > 100) throw new IllegalArgumentException("Cadre must be at most 100 characters.");
        return cadre.isEmpty() ? null : cadre;
    }
}
