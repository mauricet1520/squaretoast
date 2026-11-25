package com.squaretoast.payment.adapter.stripe;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Test oracle: signs a payload the way Stripe documents it (HMAC-SHA256 over "t.payload"). */
public final class StripeSignatures {

    private StripeSignatures() {}

    public static String v1(String secret, long timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String header(String secret, long timestamp, String payload) {
        return "t=" + timestamp + ",v1=" + v1(secret, timestamp, payload);
    }
}
