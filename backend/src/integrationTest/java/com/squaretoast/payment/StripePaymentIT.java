package com.squaretoast.payment;

import static com.squaretoast.testsupport.PaymentFixture.chargeEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.payment.GatewayException;
import com.squaretoast.payment.adapter.stripe.FakeStripe;
import com.squaretoast.payment.adapter.stripe.StripeSignatures;
import com.squaretoast.testsupport.PaymentFixture;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** Slice 4: destination charges with application_fee_amount, signed idempotent webhooks, margin ledger. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StripePaymentIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    static final FakeStripe STRIPE = new FakeStripe();
    static final String WEBHOOK_SECRET = "whsec_it_current";
    static final long STRIPE_FEE = 175;

    @DynamicPropertySource
    static void stripe(DynamicPropertyRegistry r) {
        r.add("squaretoast.stripe.api-base", STRIPE::baseUrl);
        r.add("squaretoast.stripe.secret-key", () -> "sk_test_it");
        r.add("squaretoast.stripe.webhook-secrets", () -> "whsec_it_previous," + WEBHOOK_SECRET);
    }

    @AfterAll
    static void stopStripe() {
        STRIPE.close();
    }

    @Autowired DataSource dataSource;
    @Autowired PaymentService payments;
    @LocalServerPort int port;

    PaymentFixture f;
    UUID loc;
    UUID check;

    /** Plan: card-present 0.15% + 5c, card-not-present 2.90% + 30c. */
    @BeforeEach
    void seed() {
        STRIPE.drain();
        STRIPE.fallback(r -> {
            if (r.path().equals("/v1/payment_intents")) {
                String id = "pi_" + String.valueOf(r.header("Idempotency-Key")).replace("-", "");
                return new FakeStripe.Response(200, "{\"id\":\"%s\",\"object\":\"payment_intent\",\"client_secret\":\"%s_secret_x\",\"status\":\"requires_payment_method\"}"
                        .formatted(id, id));
            }
            if (r.path().startsWith("/v1/balance_transactions/")) {
                return new FakeStripe.Response(200, "{\"id\":\"%s\",\"object\":\"balance_transaction\",\"fee\":%d,\"currency\":\"usd\"}"
                        .formatted(r.path().substring(r.path().lastIndexOf('/') + 1), STRIPE_FEE));
            }
            return new FakeStripe.Response(404, "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}");
        });
        f = new PaymentFixture(dataSource, port, WEBHOOK_SECRET);
        loc = f.location(15, 5, 290, 30);
        check = f.openCheck(loc);
    }

    private StartedPayment start(UUID key, CardMethod method, long amount, long tip) {
        return payments.start(new CardPaymentCommand(key, check, method, amount, tip));
    }

    private static String evt() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    private List<FakeStripe.Request> balanceLookups() {
        return STRIPE.drain().stream().filter(r -> r.path().startsWith("/v1/balance_transactions/")).toList();
    }

    // ---------------------------------------------------------------- starting a payment

    @Test
    void cardPresentPaymentChargesThePlanSpreadOnAmountInclTip() {
        UUID key = UUID.randomUUID();

        StartedPayment p = start(key, CardMethod.CARD_PRESENT, 5000, 800);

        // floor(5000 x 15 / 10000) + 5 = 7 + 5
        FakeStripe.Request r = STRIPE.takeRequest();
        assertThat(r.header("Idempotency-Key")).isEqualTo(key.toString());
        assertThat(r.form())
                .containsEntry("amount", List.of("5000"))
                .containsEntry("application_fee_amount", List.of("12"))
                .containsEntry("transfer_data[destination]", List.of(PaymentFixture.CONNECTED_ACCOUNT))
                .containsEntry("on_behalf_of", List.of(PaymentFixture.CONNECTED_ACCOUNT))
                .containsEntry("payment_method_types[]", List.of("card_present"));
        Map<String, Object> row = f.payment(key);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("method", "CARD_PRESENT")
                .containsEntry("processor", "STRIPE").containsEntry("processor_intent_ref", p.intentRef())
                .containsEntry("amount_cents", 5000L).containsEntry("tip_cents", 800L)
                .containsEntry("application_fee_cents", 12L).containsEntry("currency", "USD")
                .containsEntry("check_id", check);
        assertThat(p.paymentId()).isEqualTo(row.get("id"));
        assertThat(p.clientSecret()).isEqualTo(p.intentRef() + "_secret_x");
    }

    @Test
    void qrPaymentUsesTheCardNotPresentSpread() {
        UUID key = UUID.randomUUID();

        start(key, CardMethod.QR_CNP, 5000, 0);

        // floor(5000 x 290 / 10000) + 30 = 145 + 30
        assertThat(STRIPE.takeRequest().form())
                .containsEntry("application_fee_amount", List.of("175"))
                .containsEntry("automatic_payment_methods[enabled]", List.of("true"));
        assertThat(f.payment(key)).containsEntry("method", "QR_CNP").containsEntry("application_fee_cents", 175L);
    }

    @Test
    void retryWithTheSameKeyReturnsTheSamePaymentWithoutChargingTwice() {
        UUID key = UUID.randomUUID();

        StartedPayment first = start(key, CardMethod.CARD_PRESENT, 2000, 0);
        StartedPayment again = start(key, CardMethod.CARD_PRESENT, 2000, 0);

        assertThat(again).isEqualTo(first);
        assertThat(f.paymentCount(check)).isEqualTo(1);
        assertThat(STRIPE.drain()).hasSize(1);
    }

    @Test
    void reusingAKeyForADifferentChargeIsRejected() {
        UUID key = UUID.randomUUID();
        start(key, CardMethod.CARD_PRESENT, 2000, 0);

        assertThatThrownBy(() -> start(key, CardMethod.CARD_PRESENT, 2500, 0))
                .isInstanceOf(PaymentException.IdempotencyConflict.class);
        assertThatThrownBy(() -> start(key, CardMethod.QR_CNP, 2000, 0))
                .isInstanceOf(PaymentException.IdempotencyConflict.class);
        assertThat(f.paymentCount(check)).isEqualTo(1);
    }

    @Test
    void processorFailureLeavesAPendingPaymentThatARetryCompletesWithTheSameKey() {
        UUID key = UUID.randomUUID();
        STRIPE.enqueue(500, "{\"error\":{\"type\":\"api_error\"}}");

        assertThatThrownBy(() -> start(key, CardMethod.CARD_PRESENT, 3000, 0)).isInstanceOf(GatewayException.class);
        assertThat(f.payment(key)).containsEntry("status", "PENDING").containsEntry("processor_intent_ref", null);

        StartedPayment p = start(key, CardMethod.CARD_PRESENT, 3000, 0);

        List<FakeStripe.Request> calls = STRIPE.drain();
        assertThat(calls).hasSize(2).allSatisfy(r -> assertThat(r.header("Idempotency-Key")).isEqualTo(key.toString()));
        assertThat(f.payment(key)).containsEntry("processor_intent_ref", p.intentRef());
        assertThat(f.paymentCount(check)).isEqualTo(1);
    }

    @Test
    void paymentOnACheckThatIsNotOpenIsRejectedBeforeCallingStripe() {
        f.setCheckStatus(check, "PAID");

        assertThatThrownBy(() -> start(UUID.randomUUID(), CardMethod.CARD_PRESENT, 1000, 0))
                .isInstanceOf(PaymentException.CheckNotOpen.class);
        assertThat(STRIPE.drain()).isEmpty();
    }

    @Test
    void unknownCheckAndUnconfiguredMerchantAreRejected() {
        assertThatThrownBy(() -> payments.start(new CardPaymentCommand(UUID.randomUUID(), UUID.randomUUID(), CardMethod.CARD_PRESENT, 1000, 0)))
                .isInstanceOf(PaymentException.NotFound.class);
        UUID cashCheck = f.openCheck(f.locationWithoutProcessor());
        assertThatThrownBy(() -> payments.start(new CardPaymentCommand(UUID.randomUUID(), cashCheck, CardMethod.CARD_PRESENT, 1000, 0)))
                .isInstanceOf(PaymentException.NotConfigured.class);
        assertThat(STRIPE.drain()).isEmpty();
    }

    // ---------------------------------------------------------------- webhooks

    @Test
    void webhookWithABadOrMissingSignatureIs400AndRecordsNothing() {
        String id = evt();
        String payload = chargeEvent(id, "charge.succeeded", "pi_x", 1000, "txn_x");
        long now = System.currentTimeMillis() / 1000;

        assertThat(f.postWebhook(payload, StripeSignatures.header("whsec_attacker", now, payload)).statusCode()).isEqualTo(400);
        assertThat(f.postWebhook(payload, null).statusCode()).isEqualTo(400);
        assertThat(f.postWebhook(payload, StripeSignatures.header(WEBHOOK_SECRET, now - 600, payload)).statusCode()).isEqualTo(400);
        assertThat(f.webhookEventCount(id)).isZero();
    }

    @Test
    void webhookSignedWithThePreviousSecretIsAccepted() {
        String id = evt();
        String payload = chargeEvent(id, "charge.succeeded", "pi_unknown", 1000, null);
        long now = System.currentTimeMillis() / 1000;

        assertThat(f.postWebhook(payload, StripeSignatures.header("whsec_it_previous", now, payload)).statusCode()).isEqualTo(200);
        assertThat(f.webhookEventCount(id)).isOne();
    }

    @Test
    void succeededChargeSettlesIntoABalancedJournalWithMarginEqualToFeeMinusStripeFee() throws Exception {
        StartedPayment p = start(UUID.randomUUID(), CardMethod.CARD_PRESENT, 5000, 800);
        String id = evt();
        String payload = chargeEvent(id, "charge.succeeded", p.intentRef(), 5000, "txn_1");

        HttpResponse<String> res = f.postWebhook(payload);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(f.payment(keyOf(p))).containsEntry("status", "SUCCEEDED");
        Map<String, Long> lines = f.settlementLines(p.intentRef());
        assertThat(lines).containsOnly(
                Map.entry("MERCHANT_CASH", 5000L - 12),
                Map.entry("PLATFORM_FEE", 12L - STRIPE_FEE),
                Map.entry("PROCESSOR_FEE", STRIPE_FEE),
                Map.entry("TIPS_PAYABLE", -800L),
                Map.entry("SALES", -4200L));
        assertThat(lines.values().stream().mapToLong(Long::longValue).sum()).isZero();
        assertThat(f.settlementLocation(p.intentRef())).isEqualTo(loc);
        Map<String, Object> event = f.webhookEvent(id);
        assertThat(event).containsEntry("processor", "STRIPE").containsEntry("type", "charge.succeeded");
        assertThat(event.get("processed_at")).isNotNull();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        assertThat(event).containsEntry("payload_sha256", sha);
    }

    @Test
    void replayedEventIsProcessedOnce() {
        StartedPayment p = start(UUID.randomUUID(), CardMethod.CARD_PRESENT, 5000, 0);
        STRIPE.drain();
        String id = evt();
        String payload = chargeEvent(id, "charge.succeeded", p.intentRef(), 5000, "txn_2");

        assertThat(f.postWebhook(payload).statusCode()).isEqualTo(200);
        assertThat(f.postWebhook(payload).statusCode()).isEqualTo(200);

        assertThat(f.webhookEventCount(id)).isOne();
        assertThat(f.settlementCount(p.intentRef())).isOne();
        assertThat(balanceLookups()).hasSize(1);
    }

    @Test
    void concurrentDeliveriesOfTheSameEventSettleOnce() throws Exception {
        StartedPayment p = start(UUID.randomUUID(), CardMethod.CARD_PRESENT, 5000, 0);
        String payload = chargeEvent(evt(), "charge.succeeded", p.intentRef(), 5000, "txn_3");
        int n = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return f.postWebhook(payload).statusCode();
                }));
            }
            go.countDown();
            for (Future<Integer> r : results) {
                assertThat(r.get()).isEqualTo(200);
            }
        }
        assertThat(f.settlementCount(p.intentRef())).isOne();
    }

    @Test
    void aDifferentEventForTheSameChargeDoesNotSettleTwice() {
        StartedPayment p = start(UUID.randomUUID(), CardMethod.CARD_PRESENT, 5000, 0);

        assertThat(f.postWebhook(chargeEvent(evt(), "charge.succeeded", p.intentRef(), 5000, "txn_4")).statusCode()).isEqualTo(200);
        assertThat(f.postWebhook(chargeEvent(evt(), "charge.updated", p.intentRef(), 5000, "txn_4")).statusCode()).isEqualTo(200);

        assertThat(f.settlementCount(p.intentRef())).isOne();
    }

    @Test
    void chargeWithoutABalanceTransactionYetSettlesOnTheLaterUpdate() {
        StartedPayment p = start(UUID.randomUUID(), CardMethod.CARD_PRESENT, 5000, 0);

        assertThat(f.postWebhook(chargeEvent(evt(), "charge.succeeded", p.intentRef(), 5000, null)).statusCode()).isEqualTo(200);
        assertThat(f.payment(keyOf(p))).containsEntry("status", "SUCCEEDED");
        assertThat(f.settlementCount(p.intentRef())).isZero();

        assertThat(f.postWebhook(chargeEvent(evt(), "charge.updated", p.intentRef(), 5000, "txn_5")).statusCode()).isEqualTo(200);
        assertThat(f.settlementCount(p.intentRef())).isOne();
    }

    @Test
    void chargeForADifferentAmountThanThePaymentIsNotSettled() {
        StartedPayment p = start(UUID.randomUUID(), CardMethod.CARD_PRESENT, 5000, 0);

        assertThat(f.postWebhook(chargeEvent(evt(), "charge.succeeded", p.intentRef(), 4999, "txn_6")).statusCode()).isEqualTo(200);

        assertThat(f.settlementCount(p.intentRef())).isZero();
        assertThat(f.payment(keyOf(p))).containsEntry("status", "PENDING");
    }

    @Test
    void eventForAnUnknownIntentOrOfAnotherTypeIsAcknowledgedAndIgnored() {
        String unknown = evt();
        String other = evt();

        assertThat(f.postWebhook(chargeEvent(unknown, "charge.succeeded", "pi_notours", 1000, "txn_7")).statusCode()).isEqualTo(200);
        assertThat(f.postWebhook("{\"id\":\"%s\",\"object\":\"event\",\"type\":\"customer.created\",\"data\":{\"object\":{\"id\":\"cus_1\"}}}"
                .formatted(other)).statusCode()).isEqualTo(200);

        assertThat(f.webhookEventCount(unknown)).isOne();
        assertThat(f.webhookEventCount(other)).isOne();
        assertThat(f.settlementCount("pi_notours")).isZero();
    }

    @Test
    void malformedSignedPayloadIs400() {
        assertThat(f.postWebhook("{not json").statusCode()).isEqualTo(400);
        assertThat(f.postWebhook("{\"object\":\"event\",\"type\":\"charge.succeeded\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void failureWhileHandlingRollsBackSoStripesRetryIsProcessed() {
        StartedPayment p = start(UUID.randomUUID(), CardMethod.CARD_PRESENT, 5000, 0);
        String id = evt();
        String payload = chargeEvent(id, "charge.succeeded", p.intentRef(), 5000, "txn_8");
        STRIPE.drain();
        STRIPE.enqueue(500, "{\"error\":{\"type\":\"api_error\"}}");

        assertThat(f.postWebhook(payload).statusCode()).isEqualTo(500);
        assertThat(f.webhookEventCount(id)).isZero();
        assertThat(f.settlementCount(p.intentRef())).isZero();

        assertThat(f.postWebhook(payload).statusCode()).isEqualTo(200);
        assertThat(f.settlementCount(p.intentRef())).isOne();
    }

    private UUID keyOf(StartedPayment p) {
        return f.idempotencyKeyOf(p.paymentId());
    }
}
