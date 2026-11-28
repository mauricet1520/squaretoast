package com.squaretoast.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Session and CSRF tokens: 256 random bits, base64url; only their SHA-256 is stored. */
final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {}

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time compare of a presented secret against a stored hash. */
    static boolean matchesHash(String presented, byte[] storedHash) {
        return MessageDigest.isEqual(sha256(presented), storedHash);
    }
}
