package com.squaretoast.payment.adapter.stripe;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Stripe-Signature: t + v1 HMAC-SHA256, 300 s tolerance, constant-time compare, secret rotation. */
class StripeWebhookVerifierTest {

    private static final String SECRET = "whsec_test_current";
    private static final String OLD_SECRET = "whsec_test_previous";
    private static final long NOW = 1_800_000_000L;
    private static final String PAYLOAD = "{\"id\":\"evt_1\",\"type\":\"charge.succeeded\"}";

    private static StripeWebhookVerifier verifier(String... secrets) {
        return new StripeWebhookVerifier(List.of(secrets), Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void validSignatureIsAccepted() {
        assertThatCode(() -> verifier(SECRET).verify(bytes(PAYLOAD), StripeSignatures.header(SECRET, NOW, PAYLOAD)))
                .doesNotThrowAnyException();
    }

    @Test
    void tamperedPayloadIsRejected() {
        String header = StripeSignatures.header(SECRET, NOW, PAYLOAD);
        assertThatThrownBy(() -> verifier(SECRET).verify(bytes(PAYLOAD.replace("evt_1", "evt_2")), header))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void wrongSecretIsRejected() {
        assertThatThrownBy(() -> verifier(SECRET).verify(bytes(PAYLOAD), StripeSignatures.header("whsec_other", NOW, PAYLOAD)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void timestampOutsideToleranceIsRejectedEvenWithAValidSignature() {
        long stale = NOW - 301;
        long future = NOW + 301;
        assertThatThrownBy(() -> verifier(SECRET).verify(bytes(PAYLOAD), StripeSignatures.header(SECRET, stale, PAYLOAD)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier(SECRET).verify(bytes(PAYLOAD), StripeSignatures.header(SECRET, future, PAYLOAD)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatCode(() -> verifier(SECRET).verify(bytes(PAYLOAD), StripeSignatures.header(SECRET, NOW - 300, PAYLOAD)))
                .doesNotThrowAnyException();
    }

    @Test
    void signatureFromThePreviousSecretIsAcceptedDuringRotation() {
        assertThatCode(() -> verifier(SECRET, OLD_SECRET).verify(bytes(PAYLOAD), StripeSignatures.header(OLD_SECRET, NOW, PAYLOAD)))
                .doesNotThrowAnyException();
    }

    @Test
    void anyOfSeveralV1EntriesMayMatch() {
        String header = "t=" + NOW + ",v1=" + "00".repeat(32) + ",v1=" + StripeSignatures.v1(SECRET, NOW, PAYLOAD);
        assertThatCode(() -> verifier(SECRET).verify(bytes(PAYLOAD), header)).doesNotThrowAnyException();
    }

    @Test
    void v0SchemeIsIgnored() {
        String header = "t=" + NOW + ",v0=" + StripeSignatures.v1(SECRET, NOW, PAYLOAD);
        assertThatThrownBy(() -> verifier(SECRET).verify(bytes(PAYLOAD), header))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void missingOrMalformedHeadersAreRejected() {
        String v1 = StripeSignatures.v1(SECRET, NOW, PAYLOAD);
        for (String bad : new String[] {"", "garbage", "v1=" + v1, "t=abc,v1=" + v1, "t=" + NOW, "t=" + NOW + ",v1=zz"}) {
            assertThatThrownBy(() -> verifier(SECRET).verify(bytes(PAYLOAD), bad))
                    .as(bad).isInstanceOf(InvalidWebhookSignatureException.class);
        }
        assertThatThrownBy(() -> verifier(SECRET).verify(bytes(PAYLOAD), null))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void noConfiguredSecretRejectsEverything() {
        assertThatThrownBy(() -> verifier().verify(bytes(PAYLOAD), StripeSignatures.header("", NOW, PAYLOAD)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier("").verify(bytes(PAYLOAD), StripeSignatures.header("", NOW, PAYLOAD)))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }
}
