package com.squaretoast.admin;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Argon2id at the OWASP floor: 19 MiB memory, 2 passes, 1 lane, 16-byte salt, 32-byte hash, PHC-encoded. */
final class Passwords {

    private static final Argon2PasswordEncoder ARGON2 = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    /** Checked when the email is unknown or disabled, so the response time does not reveal which accounts exist. */
    private static final String DUMMY = hash("no such user");

    private Passwords() {}

    static String hash(String raw) {
        String h = ARGON2.encode(raw);
        if (h == null) {
            throw new IllegalStateException("argon2 returned no hash");
        }
        return h;
    }

    static boolean matches(String raw, String hash) {
        try {
            return ARGON2.matches(raw, hash);
        } catch (IllegalArgumentException e) { // not a PHC string
            return false;
        }
    }

    /** Burns the same time as a real check. */
    static void dummyCheck(String raw) {
        ARGON2.matches(raw, DUMMY);
    }
}
