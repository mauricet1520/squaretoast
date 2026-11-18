package com.squaretoast.payment.adapter.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import com.squaretoast.domain.payment.ChargeRequest;
import com.squaretoast.domain.payment.GatewayException;
import com.squaretoast.domain.payment.GatewayIntent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Destination charge over real HTTP: form body, Idempotency-Key, auth, pinned API version, error mapping. */
class StripeConnectAdapterTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final String SECRET_KEY = "sk_test_adapter";
    private static final String INTENT_JSON =
            "{\"id\":\"pi_123\",\"object\":\"payment_intent\",\"client_secret\":\"pi_123_secret_abc\",\"status\":\"requires_payment_method\"}";

    FakeStripe stripe;
    StripeConnectAdapter adapter;

    @BeforeEach
    void start() {
        stripe = new FakeStripe();
        adapter = new StripeConnectAdapter(stripe.baseUrl(), SECRET_KEY);
    }

    @AfterEach
    void stop() {
        stripe.close();
    }

    private static Money usd(long cents) {
        return Money.ofCents(cents, USD);
    }

    private static ChargeRequest charge(UUID key, UUID check) {
        return new ChargeRequest(key, check, usd(5000), usd(800), usd(30), "acct_1Merchant");
    }

    @Test
    void cardPresentIntentIsADestinationChargeOnBehalfOfTheMerchant() {
        UUID key = UUID.randomUUID();
        UUID check = UUID.randomUUID();
        stripe.enqueue(200, INTENT_JSON);

        GatewayIntent intent = adapter.createCardPresentIntent(charge(key, check));

        assertThat(intent.intentRef()).isEqualTo("pi_123");
        assertThat(intent.clientSecret()).isEqualTo("pi_123_secret_abc");
        FakeStripe.Request r = stripe.takeRequest();
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/v1/payment_intents");
        assertThat(r.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(r.header("Idempotency-Key")).isEqualTo(key.toString());
        assertThat(r.header("Authorization")).isEqualTo("Bearer " + SECRET_KEY);
        assertThat(r.header("Stripe-Version")).isEqualTo(StripeConnectAdapter.API_VERSION);
        assertThat(r.form())
                .containsEntry("amount", List.of("5000"))
                .containsEntry("currency", List.of("usd"))
                .containsEntry("application_fee_amount", List.of("30"))
                .containsEntry("transfer_data[destination]", List.of("acct_1Merchant"))
                .containsEntry("on_behalf_of", List.of("acct_1Merchant"))
                .containsEntry("payment_method_types[]", List.of("card_present"))
                .containsEntry("metadata[check_id]", List.of(check.toString()))
                .containsEntry("metadata[tip_cents]", List.of("800"))
                .doesNotContainKey("automatic_payment_methods[enabled]");
    }

    @Test
    void cardNotPresentIntentUsesAutomaticPaymentMethods() {
        stripe.enqueue(200, INTENT_JSON);

        adapter.createCardNotPresentIntent(charge(UUID.randomUUID(), UUID.randomUUID()));

        FakeStripe.Request r = stripe.takeRequest();
        assertThat(r.form())
                .containsEntry("automatic_payment_methods[enabled]", List.of("true"))
                .containsEntry("transfer_data[destination]", List.of("acct_1Merchant"))
                .containsEntry("on_behalf_of", List.of("acct_1Merchant"))
                .containsEntry("application_fee_amount", List.of("30"))
                .doesNotContainKey("payment_method_types[]");
    }

    @Test
    void zeroApplicationFeeIsStillSentExplicitly() {
        stripe.enqueue(200, INTENT_JSON);
        adapter.createCardPresentIntent(new ChargeRequest(UUID.randomUUID(), UUID.randomUUID(), usd(100), usd(0), usd(0), "acct_1M"));
        assertThat(stripe.takeRequest().form()).containsEntry("application_fee_amount", List.of("0"));
    }

    @Test
    void processorFeeIsReadFromTheBalanceTransaction() {
        stripe.enqueue(200, "{\"id\":\"txn_9\",\"object\":\"balance_transaction\",\"amount\":5000,\"fee\":175,\"currency\":\"usd\"}");

        Money fee = adapter.processorFee("txn_9");

        assertThat(fee).isEqualTo(usd(175));
        FakeStripe.Request r = stripe.takeRequest();
        assertThat(r.method()).isEqualTo("GET");
        assertThat(r.path()).isEqualTo("/v1/balance_transactions/txn_9");
        assertThat(r.header("Authorization")).isEqualTo("Bearer " + SECRET_KEY);
    }

    @Test
    void balanceTransactionRefMustBeAStripeId() {
        assertThatThrownBy(() -> adapter.processorFee("../charges/ch_1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.processorFee("txn_")).isInstanceOf(IllegalArgumentException.class);
        assertThat(stripe.drain()).isEmpty();
    }

    @Test
    void stripeErrorsBecomeGatewayExceptionsCarryingOnlyTheErrorCode() {
        stripe.enqueue(402, "{\"error\":{\"type\":\"card_error\",\"code\":\"card_declined\",\"message\":\"Your card was declined.\"}}");

        assertThatThrownBy(() -> adapter.createCardNotPresentIntent(charge(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.code()).isEqualTo("card_declined");
                    assertThat(e.getMessage()).doesNotContain(SECRET_KEY);
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    void serverErrorsAreRetryable() {
        stripe.enqueue(500, "{\"error\":{\"type\":\"api_error\"}}");

        assertThatThrownBy(() -> adapter.createCardPresentIntent(charge(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void connectionTokensAreCreatedOnThePlatformAccountForTheTerminalLocation() {
        stripe.enqueue(200, "{\"object\":\"terminal.connection_token\",\"secret\":\"pst_test_abc\",\"location\":\"tml_Main1\"}");

        assertThat(adapter.createConnectionToken("tml_Main1")).isEqualTo("pst_test_abc");

        FakeStripe.Request r = stripe.takeRequest();
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/v1/terminal/connection_tokens");
        assertThat(r.formValue("location")).isEqualTo("tml_Main1");
        assertThat(r.header("Authorization")).isEqualTo("Bearer " + SECRET_KEY);
        assertThat(r.header("Stripe-Account")).isNull(); // destination charges: platform account, no Connect header
        assertThat(r.header("Stripe-Version")).isEqualTo(StripeConnectAdapter.API_VERSION);
    }

    @Test
    void terminalLocationRefMustBeAStripeId() {
        assertThatThrownBy(() -> adapter.createConnectionToken("tml_../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.createConnectionToken("acct_1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(stripe.drain()).isEmpty();
    }

    @Test
    void connectionTokenServerErrorsAreRetryable() {
        stripe.enqueue(503, "{\"error\":{\"type\":\"api_error\",\"code\":\"service_unavailable\"}}");
        assertThatThrownBy(() -> adapter.createConnectionToken("tml_Main1"))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.retryable()).isTrue());
    }
}
