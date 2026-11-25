package com.squaretoast.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies a check claim signed by an offline POS (plan S6-7). The token is
 * base64url(payload).base64url(SHA256withECDSA(payload)), made by Android's OfflineClaimSigner with the device's
 * Keystore key; the public key is the one the device registered at enrollment. Every failure is the same
 * {@link Invalid}, so a caller learns nothing about which check failed.
 */
@Component
class OfflineClaims {

    /** Claims live two hours; five minutes of clock skew between POS and server is tolerated. */
    static final Duration MAX_LIFETIME = Duration.ofHours(2).plusMinutes(5);
    private static final int MAX_TOKEN = 4096;
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Invalid() {
            super("invalid claim", null, false, false);
        }
    }

    /** A verified claim. {@code payload} is the exact signed bytes (compared with Arrays.equals, never equals()). */
    @SuppressWarnings("ArrayRecordComponent")
    record Claim(UUID checkId, UUID locationId, UUID deviceId, long totalCents, String currency, Instant expiresAt,
                 String nonce, byte[] payload, byte[] signature) {}

    @SuppressWarnings("ArrayRecordComponent")
    private record DeviceKey(UUID location, byte[] spki, boolean revoked) {}

    private final JdbcClient db;
    private final Clock clock;

    OfflineClaims(JdbcClient db) {
        this.db = db;
        this.clock = Clock.systemUTC();
    }

    Claim verify(@Nullable String token) {
        if (token == null || token.length() > MAX_TOKEN) {
            throw new Invalid();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2 || !PART.matcher(parts[0]).matches() || !PART.matcher(parts[1]).matches()) {
            throw new Invalid();
        }
        byte[] payload = decode(parts[0]);
        byte[] signature = decode(parts[1]);

        JsonNode p;
        try {
            p = JSON.readTree(new String(payload, StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            throw new Invalid();
        }
        if (p == null || !p.isObject()) {
            throw new Invalid();
        }
        UUID check = uuid(p, "checkId");
        UUID location = uuid(p, "locationId");
        UUID device = uuid(p, "deviceId");
        long subtotal = cents(p, "subtotalCents");
        long tax = cents(p, "taxCents");
        long service = cents(p, "serviceChargeCents");
        long total = cents(p, "totalCents");
        long expires = cents(p, "expiresAtEpochSecond");
        String currency = text(p, "currency");
        String nonce = text(p, "nonce");
        if (!CURRENCY.matcher(currency).matches() || nonce.length() > 128 || total <= 0) {
            throw new Invalid();
        }
        try {
            if (Math.addExact(Math.addExact(subtotal, tax), service) != total) {
                throw new Invalid();
            }
        } catch (ArithmeticException e) {
            throw new Invalid();
        }

        DeviceKey key = db.sql("select location_id, public_key_spki, revoked_at is not null as revoked from device where id = ?")
                .params(device)
                .query((rs, n) -> new DeviceKey(rs.getObject("location_id", UUID.class), rs.getBytes("public_key_spki"),
                        rs.getBoolean("revoked")))
                .optional().orElseThrow(Invalid::new);
        if (key.revoked() || !key.location().equals(location) || !signatureValid(key.spki(), payload, signature)) {
            throw new Invalid();
        }

        String locationCurrency = db.sql("select currency from location where id = ?").params(location)
                .query(String.class).optional().orElseThrow(Invalid::new);
        Instant now = clock.instant();
        Instant expiresAt = Instant.ofEpochSecond(expires);
        if (!currency.equals(locationCurrency) || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_LIFETIME))) {
            throw new Invalid();
        }
        return new Claim(check, location, device, total, currency, expiresAt, nonce, payload, signature);
    }

    private static boolean signatureValid(byte[] spki, byte[] payload, byte[] signature) {
        try {
            PublicKey pub = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pub);
            s.update(payload);
            return s.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] decode(String part) {
        try {
            return Base64.getUrlDecoder().decode(part);
        } catch (IllegalArgumentException e) {
            throw new Invalid();
        }
    }

    private static UUID uuid(JsonNode p, String field) {
        try {
            return UUID.fromString(text(p, field));
        } catch (IllegalArgumentException e) {
            throw new Invalid();
        }
    }

    private static String text(JsonNode p, String field) {
        JsonNode n = p.get(field);
        if (n == null || !n.isString() || n.stringValue().isEmpty()) {
            throw new Invalid();
        }
        return n.stringValue();
    }

    private static long cents(JsonNode p, String field) {
        JsonNode n = p.get(field);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 0) {
            throw new Invalid();
        }
        return n.longValue();
    }

    static boolean samePayload(Claim c, byte[] stored) {
        return Arrays.equals(c.payload(), stored);
    }
}
