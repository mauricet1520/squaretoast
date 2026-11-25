package com.squaretoast.payment.adapter.stripe;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Verifies {@code Stripe-Signature: t=<unix>,v1=<hex>[,v1=...]}: HMAC-SHA256 of {@code t + "." + rawBody} with an
 * endpoint secret, compared in constant time, timestamp within 300 s either way. Several secrets may be configured so
 * a rotated secret keeps working until Stripe stops using it. No secret configured means nothing verifies.
 */
public final class StripeWebhookVerifier {

    public static final Duration TOLERANCE = Duration.ofMinutes(5);

    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,12}");
    private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final List<byte[]> secrets;
    private final Clock clock;

    public StripeWebhookVerifier(List<String> secrets, Clock clock) {
        this.secrets = secrets.stream().filter(s -> !s.isBlank())
                .map(s -> s.strip().getBytes(StandardCharsets.UTF_8)).toList();
        this.clock = clock;
    }

    public void verify(byte[] payload, @Nullable String signatureHeader) {
        if (signatureHeader == null || secrets.isEmpty()) {
            throw new InvalidWebhookSignatureException();
        }
        String timestamp = null;
        List<byte[]> candidates = new ArrayList<>();
        for (String part : signatureHeader.split(",", -1)) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).strip();
            String value = part.substring(eq + 1).strip();
            if (key.equals("t")) {
                timestamp = value;
            } else if (key.equals("v1") && HEX_SHA256.matcher(value).matches()) {
                candidates.add(HexFormat.of().parseHex(value));
            }
        }
        if (timestamp == null || !TIMESTAMP.matcher(timestamp).matches() || candidates.isEmpty()) {
            throw new InvalidWebhookSignatureException();
        }
        long age = clock.instant().getEpochSecond() - Long.parseLong(timestamp);
        if (Math.abs(age) > TOLERANCE.toSeconds()) {
            throw new InvalidWebhookSignatureException();
        }
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (byte[] secret : secrets) {
            byte[] expected = hmac(secret, prefix, payload);
            for (byte[] candidate : candidates) {
                matched |= MessageDigest.isEqual(expected, candidate);
            }
        }
        if (!matched) {
            throw new InvalidWebhookSignatureException();
        }
    }

    private static byte[] hmac(byte[] secret, byte[] prefix, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(prefix);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
