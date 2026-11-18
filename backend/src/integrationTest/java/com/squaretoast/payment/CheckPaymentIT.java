package com.squaretoast.payment;

import static com.squaretoast.testsupport.DeviceFixture.body;
import static com.squaretoast.testsupport.DeviceFixture.payBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.payment.adapter.stripe.FakeStripe;
import com.squaretoast.testsupport.DeviceFixture;
import com.squaretoast.testsupport.DeviceFixture.Device;
import com.squaretoast.testsupport.DeviceFixture.Line;
import com.squaretoast.testsupport.MenuFixture;
import com.squaretoast.testsupport.PaymentFixture;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Slice 5b: the server computes what a check owes; the device only chooses the tip. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CheckPaymentIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    static final FakeStripe STRIPE = new FakeStripe();

    @DynamicPropertySource
    static void stripe(DynamicPropertyRegistry r) {
        r.add("squaretoast.stripe.api-base", STRIPE::baseUrl);
        r.add("squaretoast.stripe.secret-key", () -> "sk_test_it");
        r.add("squaretoast.stripe.webhook-secrets", () -> "whsec_it");
    }

    @AfterAll
    static void stopStripe() {
        STRIPE.close();
    }

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    DeviceFixture d;
    MenuFixture m;
    PaymentFixture p;
    UUID loc;
    UUID burger;
    UUID fries;
    Device pos;

    /** 7.25% food tax; burger $15, fries $10. */
    @BeforeEach
    void seed() {
        STRIPE.drain();
        STRIPE.fallback(r -> {
            if (r.path().equals("/v1/payment_intents")) {
                String id = "pi_" + String.valueOf(r.header("Idempotency-Key")).replace("-", "");
                return new FakeStripe.Response(200,
                        "{\"id\":\"%s\",\"object\":\"payment_intent\",\"client_secret\":\"%s_secret_x\"}".formatted(id, id));
            }
            if (r.path().equals("/v1/terminal/connection_tokens")) {
                return new FakeStripe.Response(200, "{\"object\":\"terminal.connection_token\",\"secret\":\"pst_test_it\"}");
            }
            return new FakeStripe.Response(404, "{\"error\":{\"code\":\"resource_missing\"}}");
        });
        d = new DeviceFixture(dataSource, port);
        m = new MenuFixture(dataSource, port);
        p = new PaymentFixture(dataSource, port, "whsec_it");
        loc = p.location(15, 5, 290, 30);
        d.taxRule("US", "FOOD", 72_500);
        burger = m.menuItem(loc, "Burger", 1500);
        fries = m.menuItem(loc, "Fries", 1000);
        pos = d.enrolledDevice(loc);
        d.terminalLocation(loc, "tml_Main1");
    }

    /** Places 2 burgers + 1 fries through the real sync path and returns the order's check. */
    private UUID placeOrder(UUID order) {
        HttpResponse<String> r = d.sync(pos.token(), List.of(d.orderPlaced(UUID.randomUUID(), order, loc,
                Map.of(1, UUID.randomUUID()),
                List.of(new Line(burger, 1, 1, 2, 1500), new Line(fries, 2, 1, 1, 1000)))));
        assertThat(DeviceFixture.results(r).values()).allMatch(v -> v.get(0).equals("APPLIED"));
        return d.checkOf(order);
    }

    private FakeStripe.Request lastIntent() {
        return STRIPE.drain().stream().filter(r -> r.path().equals("/v1/payment_intents")).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void chargesSubtotalPlusTaxPlusTipComputedOnTheServer() {
        UUID check = placeOrder(UUID.randomUUID());
        UUID key = UUID.randomUUID();

        HttpResponse<String> r = d.pay(pos.token(), check, payBody(key, "CARD_PRESENT", 600));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(body(r).get("clientSecret").asString()).endsWith("_secret_x");
        assertThat(body(r).get("paymentId").asString()).isNotBlank();
        FakeStripe.Request intent = lastIntent();
        assertThat(intent.formValue("amount")).isEqualTo("4890"); // 4000 + 290 tax + 600 tip
        assertThat(intent.formValue("metadata[tip_cents]")).isEqualTo("600");
        assertThat(intent.formValue("metadata[check_id]")).isEqualTo(check.toString());
        Map<String, Object> row = p.payment(key);
        assertThat(((Number) java.util.Objects.requireNonNull(row.get("amount_cents"))).longValue()).isEqualTo(4890);
        assertThat(((Number) java.util.Objects.requireNonNull(row.get("tip_cents"))).longValue()).isEqualTo(600);
    }

    @Test
    void aClientSuppliedAmountIsIgnored() {
        UUID check = placeOrder(UUID.randomUUID());
        String body = "{\"idempotencyKey\":\"%s\",\"method\":\"CARD_PRESENT\",\"tipCents\":0,\"amountCents\":1}"
                .formatted(UUID.randomUUID());

        assertThat(d.pay(pos.token(), check, body).statusCode()).isEqualTo(200);
        assertThat(lastIntent().formValue("amount")).isEqualTo("4290");
    }

    @Test
    void retryingWithTheSameKeyReturnsTheSamePaymentButASecondPaymentFindsNothingDue() {
        UUID check = placeOrder(UUID.randomUUID());
        UUID key = UUID.randomUUID();
        String first = body(d.pay(pos.token(), check, payBody(key, "CARD_PRESENT", 0))).get("paymentId").asString();

        HttpResponse<String> retry = d.pay(pos.token(), check, payBody(key, "CARD_PRESENT", 0));
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(body(retry).get("paymentId").asString()).isEqualTo(first);

        HttpResponse<String> second = d.pay(pos.token(), check, payBody(UUID.randomUUID(), "QR_CNP", 0));
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("NOTHING_DUE");
        assertThat(p.paymentCount(check)).isEqualTo(1);
    }

    @Test
    void aSplitCheckOwesOnlyItsShare() {
        UUID order = UUID.randomUUID();
        UUID checkA = placeOrder(order);
        UUID checkB = UUID.randomUUID();
        UUID burgerLine = (UUID) d.row("select id from order_line where order_id = ? and menu_item_id = ?", order, burger)
                .get("id");
        m.exec("insert into guest_check(id, order_id, label) values (?, ?, 'Seat 2')", checkB, order);
        m.exec("update check_allocation set numerator = 1, denominator = 2 where check_id = ? and order_line_id = ?",
                checkA, burgerLine);
        m.exec("insert into check_allocation(check_id, order_line_id, numerator, denominator) values (?, ?, 1, 2)",
                checkB, burgerLine);

        d.pay(pos.token(), checkA, payBody(UUID.randomUUID(), "CARD_PRESENT", 0));
        assertThat(lastIntent().formValue("amount")).isEqualTo("2681"); // half of 2 burgers (1500) + fries 1000 + 181.25 -> 181 tax
        d.pay(pos.token(), checkB, payBody(UUID.randomUUID(), "CARD_PRESENT", 0));
        assertThat(lastIntent().formValue("amount")).isEqualTo("1609"); // other half 1500 + 108.75 -> 109 tax
    }

    @Test
    void checksAreScopedToTheDevicesLocation() {
        UUID check = placeOrder(UUID.randomUUID());
        Device elsewhere = d.enrolledDevice(p.location(15, 5, 290, 30));

        assertThat(d.pay(elsewhere.token(), check, payBody(UUID.randomUUID(), "CARD_PRESENT", 0)).statusCode()).isEqualTo(404);
        assertThat(d.pay(pos.token(), UUID.randomUUID(), payBody(UUID.randomUUID(), "CARD_PRESENT", 0)).statusCode()).isEqualTo(404);
        assertThat(p.paymentCount(check)).isZero();
    }

    @Test
    void invalidRequestsAreRejected() {
        UUID check = placeOrder(UUID.randomUUID());
        assertThat(d.pay(pos.token(), check, payBody(UUID.randomUUID(), "CARD_PRESENT", -1)).statusCode()).isEqualTo(400);
        assertThat(d.pay(pos.token(), check, payBody(UUID.randomUUID(), "CASH", 0)).statusCode()).isEqualTo(400);
        assertThat(d.pay(pos.token(), check, "{\"method\":\"CARD_PRESENT\",\"tipCents\":0}").statusCode()).isEqualTo(400);
        assertThat(p.paymentCount(check)).isZero();
    }

    @Test
    void aClosedCheckCannotBeCharged() {
        UUID check = placeOrder(UUID.randomUUID());
        p.setCheckStatus(check, "PAID");
        assertThat(d.pay(pos.token(), check, payBody(UUID.randomUUID(), "CARD_PRESENT", 0)).statusCode()).isEqualTo(409);
    }

    @Test
    void connectionTokensAreScopedToTheLocationsTerminalLocation() {
        HttpResponse<String> r = d.connectionToken(pos.token());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(body(r).get("secret").asString()).isEqualTo("pst_test_it");
        FakeStripe.Request req = STRIPE.drain().getLast();
        assertThat(req.path()).isEqualTo("/v1/terminal/connection_tokens");
        assertThat(req.formValue("location")).isEqualTo("tml_Main1");

        d.terminalLocation(loc, null);
        assertThat(d.connectionToken(pos.token()).statusCode()).isEqualTo(409);
    }
}
