package com.squaretoast.payment;

import static com.squaretoast.testsupport.DeviceFixture.body;
import static com.squaretoast.testsupport.DeviceFixture.claimPayload;
import static com.squaretoast.testsupport.DeviceFixture.signClaim;
import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.payment.adapter.stripe.FakeStripe;
import com.squaretoast.testsupport.DeviceFixture;
import com.squaretoast.testsupport.DeviceFixture.Signer;
import com.squaretoast.testsupport.PaymentFixture;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.node.ObjectNode;

/** Slice 6: a guest pays a check that only an offline POS knows about, using the claim the POS signed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OfflineClaimIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    static final FakeStripe STRIPE = new FakeStripe();

    @DynamicPropertySource
    static void stripe(DynamicPropertyRegistry r) {
        r.add("squaretoast.stripe.api-base", STRIPE::baseUrl);
        r.add("squaretoast.stripe.secret-key", () -> "sk_test_it");
        r.add("squaretoast.stripe.publishable-key", () -> "pk_test_it");
        r.add("squaretoast.stripe.webhook-secrets", () -> "whsec_it");
    }

    @AfterAll
    static void stopStripe() {
        STRIPE.close();
    }

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    DeviceFixture d;
    PaymentFixture p;
    UUID loc;
    Signer pos;

    @BeforeEach
    void seed() {
        STRIPE.drain();
        STRIPE.fallback(r -> {
            if (r.path().equals("/v1/payment_intents")) {
                String id = "pi_" + String.valueOf(r.header("Idempotency-Key")).replace("-", "");
                return new FakeStripe.Response(200,
                        "{\"id\":\"%s\",\"object\":\"payment_intent\",\"client_secret\":\"%s_secret_x\"}".formatted(id, id));
            }
            if (r.path().startsWith("/v1/balance_transactions/")) {
                return new FakeStripe.Response(200,
                        "{\"id\":\"txn_1\",\"object\":\"balance_transaction\",\"fee\":150,\"currency\":\"usd\"}");
            }
            return new FakeStripe.Response(404, "{\"error\":{\"code\":\"resource_missing\"}}");
        });
        d = new DeviceFixture(dataSource, port);
        p = new PaymentFixture(dataSource, port, "whsec_it");
        loc = p.location(15, 5, 290, 30);
        pos = d.enrolledSigner(loc);
    }

    private static long inOneHour() {
        return Instant.now().plusSeconds(3600).getEpochSecond();
    }

    /** A claim for 4000 + 290 tax = 4290, valid for an hour. */
    private ObjectNode claim(UUID check, String nonce) {
        return claimPayload(check, loc, pos.device().id(), 4000, 290, 0, 4290, "USD", inOneHour(), nonce);
    }

    private String signed(ObjectNode payload) {
        return signClaim(pos.keys(), payload.toString());
    }

    private String nonce() {
        return UUID.randomUUID().toString();
    }

    private long n(@Nullable Object v) {
        return ((Number) Objects.requireNonNull(v)).longValue();
    }

    private FakeStripe.Request lastIntent() {
        return STRIPE.drain().stream().filter(r -> r.path().equals("/v1/payment_intents")).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void aValidClaimChargesItsSignedTotalPlusTheTip() {
        UUID check = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        HttpResponse<String> r = d.claimPay(signed(claim(check, nonce())), key, 700);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(body(r).get("clientSecret").asString()).endsWith("_secret_x");
        assertThat(body(r).get("publishableKey").asString()).isEqualTo("pk_test_it");
        assertThat(body(r).get("totalCents").asLong()).isEqualTo(4290);
        FakeStripe.Request intent = lastIntent();
        assertThat(intent.formValue("amount")).isEqualTo("4990");
        assertThat(intent.formValue("transfer_data[destination]")).isEqualTo(PaymentFixture.CONNECTED_ACCOUNT);
        Map<String, Object> row = p.payment(key);
        assertThat(row.get("check_id")).isNull();
        assertThat(row.get("claim_check_id")).isEqualTo(check);
        assertThat(row.get("method")).isEqualTo("QR_CNP");
        assertThat(n(row.get("amount_cents"))).isEqualTo(4990);
        assertThat(n(row.get("tip_cents"))).isEqualTo(700);
        Map<String, Object> stored = d.row("select device_id, location_id, amount_cents, status from offline_check_claim where check_id = ?", check);
        assertThat(stored.get("device_id")).isEqualTo(pos.device().id());
        assertThat(stored.get("location_id")).isEqualTo(loc);
        assertThat(n(stored.get("amount_cents"))).isEqualTo(4290);
        assertThat(stored.get("status")).isEqualTo("PENDING");
    }

    @Test
    void forgedOrUnusableClaimsAreRefusedWithoutAPaymentRow() {
        UUID check = UUID.randomUUID();
        KeyPair stranger = DeviceFixture.p256KeyPair();
        Signer revoked = d.enrolledSigner(loc);
        d.revoke(revoked.device().id());
        Signer elsewhere = d.enrolledSigner(p.location(15, 5, 290, 30));
        String good = signed(claim(check, nonce()));
        String[] parts = good.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                claim(check, "x").put("totalCents", 1).put("subtotalCents", 1).put("taxCents", 0)
                        .toString().getBytes(StandardCharsets.UTF_8));

        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("tampered payload", tamperedPayload + "." + parts[1]);
        cases.put("signed by an unknown key", signClaim(stranger, claim(check, nonce()).toString()));
        cases.put("revoked device", signClaim(revoked.keys(),
                claimPayload(check, loc, revoked.device().id(), 4000, 290, 0, 4290, "USD", inOneHour(), nonce()).toString()));
        cases.put("device at another location", signClaim(elsewhere.keys(),
                claimPayload(check, loc, elsewhere.device().id(), 4000, 290, 0, 4290, "USD", inOneHour(), nonce()).toString()));
        cases.put("expired", signed(claim(check, nonce()).put("expiresAtEpochSecond", Instant.now().minusSeconds(1).getEpochSecond())));
        cases.put("lives longer than two hours", signed(claim(check, nonce())
                .put("expiresAtEpochSecond", Instant.now().plusSeconds(3 * 3600).getEpochSecond())));
        cases.put("wrong currency", signed(claim(check, nonce()).put("currency", "EUR")));
        cases.put("amounts do not add up", signed(claim(check, nonce()).put("totalCents", 9999)));
        cases.put("zero total", signed(claimPayload(check, loc, pos.device().id(), 0, 0, 0, 0, "USD", inOneHour(), nonce())));
        cases.put("not base64", "!!!.???");
        cases.put("one part", parts[0]);
        cases.put("not JSON", Base64.getUrlEncoder().withoutPadding().encodeToString("nope".getBytes(StandardCharsets.UTF_8))
                + "." + parts[1]);

        cases.forEach((name, token) -> {
            HttpResponse<String> r = d.claimPay(token, UUID.randomUUID(), 0);
            assertThat(r.statusCode()).as(name).isEqualTo(400);
            assertThat(body(r).get("error").asString()).as(name).isEqualTo("INVALID_CLAIM");
        });
        assertThat(d.count("select count(*) from payment where claim_check_id = ?", check)).isZero();
        assertThat(d.count("select count(*) from offline_check_claim where check_id = ?", check)).isZero();
    }

    @Test
    void retryingReturnsTheSamePaymentAndASecondPayerFindsNothingDue() {
        String token = signed(claim(UUID.randomUUID(), nonce()));
        UUID key = UUID.randomUUID();
        String first = body(d.claimPay(token, key, 0)).get("paymentId").asString();

        HttpResponse<String> retry = d.claimPay(token, key, 0);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(body(retry).get("paymentId").asString()).isEqualTo(first);

        HttpResponse<String> second = d.claimPay(token, UUID.randomUUID(), 0);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(body(second).get("error").asString()).isEqualTo("NOTHING_DUE");
    }

    @Test
    void aNonceCannotBeReusedForAnotherCheck() {
        String nonce = nonce();
        assertThat(d.claimPay(signed(claim(UUID.randomUUID(), nonce)), UUID.randomUUID(), 0).statusCode()).isEqualTo(200);

        UUID other = UUID.randomUUID();
        HttpResponse<String> r = d.claimPay(signed(claim(other, nonce)), UUID.randomUUID(), 0);

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(body(r).get("error").asString()).isEqualTo("CLAIM_REPLAYED");
        assertThat(d.count("select count(*) from offline_check_claim where check_id = ?", other)).isZero();
    }

    @Test
    void aNewerClaimReplacesAnUnpaidOneButNotOneAlreadyBeingPaid() {
        UUID check = UUID.randomUUID();
        String older = signed(claim(check, nonce()));
        UUID firstKey = UUID.randomUUID();
        assertThat(d.claimPay(older, firstKey, 0).statusCode()).isEqualTo(200);

        // While that payment is in flight, a different claim for the same check is refused.
        String newer = signed(claimPayload(check, loc, pos.device().id(), 5000, 363, 0, 5363, "USD", inOneHour() + 60, nonce()));
        HttpResponse<String> refused = d.claimPay(newer, UUID.randomUUID(), 0);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(body(refused).get("error").asString()).isEqualTo("CLAIM_SUPERSEDED");

        // The guest's card was declined; the POS added dessert and re-issued the claim. The newer one replaces it.
        d.row("update payment set status = 'FAILED' where idempotency_key = ? returning id", firstKey);
        HttpResponse<String> paid = d.claimPay(newer, UUID.randomUUID(), 0);
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(200);
        assertThat(lastIntent().formValue("amount")).isEqualTo("5363");
        assertThat(n(d.row("select amount_cents from offline_check_claim where check_id = ?", check).get("amount_cents")))
                .isEqualTo(5363);

        // An older claim never replaces a newer one, paid or not.
        HttpResponse<String> stale = d.claimPay(older, UUID.randomUUID(), 0);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(body(stale).get("error").asString()).isEqualTo("CLAIM_SUPERSEDED");
    }

    @Test
    void theWebhookSettlesAClaimPaymentAtTheClaimsLocationAndMarksItPaid() {
        UUID check = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        assertThat(d.claimPay(signed(claim(check, nonce())), key, 500).statusCode()).isEqualTo(200);
        String intent = String.valueOf(p.payment(key).get("processor_intent_ref"));

        HttpResponse<String> hook = p.postWebhook(PaymentFixture.chargeEvent("evt_" + UUID.randomUUID(),
                "charge.succeeded", intent, 4790, "txn_1"));

        assertThat(hook.statusCode()).isEqualTo(200);
        assertThat(p.payment(key).get("status")).isEqualTo("SUCCEEDED");
        assertThat(p.settlementCount(intent)).isEqualTo(1);
        assertThat(p.settlementLocation(intent)).isEqualTo(loc);
        assertThat(d.row("select status from offline_check_claim where check_id = ?", check).get("status")).isEqualTo("PAID");
    }

    @Test
    void aNegativeTipOrMissingKeyIsABadRequest() {
        String token = signed(claim(UUID.randomUUID(), nonce()));
        assertThat(d.claimPay(token, UUID.randomUUID(), -1).statusCode()).isEqualTo(400);
        assertThat(d.raw("POST", "/api/v1/claims/payments", "{\"claim\":\"" + token + "\",\"tipCents\":0}", null)
                .statusCode()).isEqualTo(400);
    }
}
