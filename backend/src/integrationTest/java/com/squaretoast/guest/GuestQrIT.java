package com.squaretoast.guest;

import static com.squaretoast.testsupport.DeviceFixture.body;
import static com.squaretoast.testsupport.DeviceFixture.guestOrderBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.payment.adapter.stripe.FakeStripe;
import com.squaretoast.testsupport.DeviceFixture;
import com.squaretoast.testsupport.DeviceFixture.Device;
import com.squaretoast.testsupport.DeviceFixture.GuestLine;
import com.squaretoast.testsupport.MenuFixture;
import com.squaretoast.testsupport.PaymentFixture;
import com.squaretoast.testsupport.SseClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.JsonNode;

/** Slice 6: a guest orders from the table's QR code, staff accept it, and the guest pays the server's total. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GuestQrIT {

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
    MenuFixture m;
    PaymentFixture p;
    UUID loc;
    UUID burger;
    UUID staffMeal;
    UUID patties;
    UUID cheddar;
    UUID swiss;
    UUID foreignOption;
    String qr;
    Device pos;

    /** 7.25% food tax; burger $15, cheddar +$1, swiss +$1.50 (pick at most one cheese). */
    @BeforeEach
    void seed() {
        STRIPE.drain();
        STRIPE.fallback(r -> {
            if (r.path().equals("/v1/payment_intents")) {
                String id = "pi_" + String.valueOf(r.header("Idempotency-Key")).replace("-", "");
                return new FakeStripe.Response(200,
                        "{\"id\":\"%s\",\"object\":\"payment_intent\",\"client_secret\":\"%s_secret_x\"}".formatted(id, id));
            }
            return new FakeStripe.Response(404, "{\"error\":{\"code\":\"resource_missing\"}}");
        });
        d = new DeviceFixture(dataSource, port);
        m = new MenuFixture(dataSource, port);
        p = new PaymentFixture(dataSource, port, "whsec_it");
        loc = p.location(15, 5, 290, 30);
        d.taxRule("US", "FOOD", 72_500);
        burger = m.menuItem(loc, "Burger", 1500);
        staffMeal = m.menuItem(loc, "Staff meal", 0);
        m.exec("update menu_item set visible_qr = false where id = ?", staffMeal);
        patties = m.trackedInventory(loc, "Patty", 10);
        m.itemConsumes(burger, patties, 1);
        UUID cheese = m.group(loc, "Cheese", 0, 1);
        m.link(burger, cheese);
        cheddar = m.option(cheese, "Cheddar", 100);
        swiss = m.option(cheese, "Swiss", 150);
        foreignOption = m.option(m.group(loc, "Dressing", 0, 1), "Ranch", 0);
        qr = d.table(loc, "T7");
        pos = d.enrolledDevice(loc);
    }

    private UUID order(int qty, UUID... options) {
        UUID order = UUID.randomUUID();
        HttpResponse<String> r = d.guestOrder(qr, guestOrderBody(order, List.of(new GuestLine(burger, qty, List.of(options)))));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return order;
    }

    private UUID acceptedCheck() {
        UUID order = order(2, cheddar);
        assertThat(d.commit(pos.token(), order).statusCode()).isEqualTo(200);
        return d.checkOf(order);
    }

    private long n(@Nullable Object v) {
        return ((Number) Objects.requireNonNull(v)).longValue();
    }

    @Test
    void theTableShowsItsLabelAndOnlyQrVisibleItems() {
        HttpResponse<String> r = d.guestTable(qr);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("table").asString()).isEqualTo("T7");
        assertThat(b.get("currency").asString()).isEqualTo("USD");
        List<String> ids = new ArrayList<>();
        b.get("menu").get("items").forEach(i -> ids.add(i.get("id").asString()));
        assertThat(ids).contains(burger.toString()).doesNotContain(staffMeal.toString());
        assertThat(MenuFixture.menuOption(b.get("menu"), burger, cheddar).get("priceDeltaCents").asLong()).isEqualTo(100);

        assertThat(d.guestTable("qrnosuchtable").statusCode()).isEqualTo(404);
    }

    @Test
    void theServerPricesAGuestOrderAndItWaitsForStaffBeforeTakingStock() {
        UUID order = order(2, cheddar);

        Map<String, Object> o = d.row("select status, table_id is not null as has_table, fulfillment_channel from orders where id = ?", order);
        assertThat(o.get("status")).isEqualTo("OPEN");
        assertThat(o.get("has_table")).isEqualTo(true);
        assertThat(o.get("fulfillment_channel")).isEqualTo("DINE_IN");
        assertThat(d.count("select count(*) from order_line where order_id = ? and unit_price_cents = 1500 and qty = 2", order)).isEqualTo(1);
        assertThat(d.count("""
                select count(*) from order_line_modifier m join order_line l on l.id = m.order_line_id
                where l.order_id = ? and m.price_delta_cents = 100 and m.modifier_option_id = ?""", order, cheddar)).isEqualTo(1);
        assertThat(d.count("select count(*) from course where order_id = ? and state <> 'HOLD'", order)).isZero();
        assertThat(m.onHand(patties)).isEqualTo(10);

        assertThat(d.commit(pos.token(), order).statusCode()).isEqualTo(200);
        assertThat(m.status(order)).isEqualTo("COMMITTED");
        assertThat(m.onHand(patties)).isEqualTo(8);
    }

    @Test
    void resendingTheSameOrderReturnsTheSameOrder() {
        UUID order = UUID.randomUUID();
        String b = guestOrderBody(order, List.of(new GuestLine(burger, 1, List.of())));
        HttpResponse<String> first = d.guestOrder(qr, b);
        HttpResponse<String> again = d.guestOrder(qr, b);

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(body(again).get("checkId").asString()).isEqualTo(body(first).get("checkId").asString());
        assertThat(d.count("select count(*) from order_line where order_id = ?", order)).isEqualTo(1);
    }

    @Test
    void invalidGuestOrdersAreRefusedAndWriteNothing() {
        List<GuestLine> tooManyLines = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            tooManyLines.add(new GuestLine(burger, 1, List.of()));
        }
        Map<String, List<GuestLine>> cases = Map.of(
                "qty 21", List.of(new GuestLine(burger, 21, List.of())),
                "qty 0", List.of(new GuestLine(burger, 0, List.of())),
                "31 lines", tooManyLines,
                "no lines", List.of(),
                "option from another group", List.of(new GuestLine(burger, 1, List.of(foreignOption))),
                "two cheeses in a pick-one group", List.of(new GuestLine(burger, 1, List.of(cheddar, swiss))),
                "item hidden from QR", List.of(new GuestLine(staffMeal, 1, List.of())),
                "unknown item", List.of(new GuestLine(UUID.randomUUID(), 1, List.of())));
        cases.forEach((name, lines) -> {
            UUID order = UUID.randomUUID();
            HttpResponse<String> r = d.guestOrder(qr, guestOrderBody(order, lines));
            assertThat(r.statusCode()).as(name).isEqualTo(422);
            assertThat(body(r).get("error").asString()).as(name).isEqualTo("INVALID_ORDER");
            assertThat(d.count("select count(*) from orders where id = ?", order)).as(name).isZero();
        });

        assertThat(d.guestOrder(qr, "{\"lines\":[]}").statusCode()).isEqualTo(400);
        assertThat(d.guestOrder("qrnosuchtable", guestOrderBody(UUID.randomUUID(),
                List.of(new GuestLine(burger, 1, List.of())))).statusCode()).isEqualTo(404);
    }

    @Test
    void anEightySixedItemIsRefusedUpFront() {
        m.eightySix(loc, patties, true);
        UUID order = UUID.randomUUID();

        HttpResponse<String> r = d.guestOrder(qr, guestOrderBody(order, List.of(new GuestLine(burger, 1, List.of()))));

        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(body(r).get("error").asString()).isEqualTo("ITEM_UNAVAILABLE");
        assertThat(d.count("select count(*) from orders where id = ?", order)).isZero();
    }

    @Test
    void aGuestOrderIsAnnouncedOnTheKdsStreamForStaffToAccept() throws Exception {
        Device kds = d.enrolledDevice(loc);
        try (SseClient stream = SseClient.open(d.kdsStreamUrl(d.streamTicket(kds.token()), null), null)) {
            UUID order = order(1);
            SseClient.Event e = stream.next(Duration.ofSeconds(5));
            assertThat(e.name()).isEqualTo("order");
            assertThat(e.data().get("orderId").asString()).isEqualTo(order.toString());
            assertThat(e.data().get("status").asString()).isEqualTo("AWAITING_ACCEPTANCE");
            assertThat(e.data().get("table").asString()).isEqualTo("T7");
        }
    }

    @Test
    void theBillShowsWhatTheCheckOwesOnceStaffAcceptTheOrder() {
        UUID order = order(2, cheddar);
        assertThat(body(d.guestChecks(qr)).get("checks")).isEmpty();
        assertThat(d.commit(pos.token(), order).statusCode()).isEqualTo(200);
        UUID check = d.checkOf(order);

        JsonNode list = body(d.guestChecks(qr)).get("checks");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("checkId").asString()).isEqualTo(check.toString());
        assertThat(list.get(0).get("dueCents").asLong()).isEqualTo(3432);

        JsonNode bill = body(d.guestCheck(qr, check));
        assertThat(bill.get("subtotalCents").asLong()).isEqualTo(3200); // 2 x (1500 + 100)
        assertThat(bill.get("taxCents").asLong()).isEqualTo(232);
        assertThat(bill.get("paidCents").asLong()).isZero();
        assertThat(bill.get("dueCents").asLong()).isEqualTo(3432);
        assertThat(bill.get("status").asString()).isEqualTo("OPEN");
        assertThat(bill.get("currency").asString()).isEqualTo("USD");
        JsonNode line = bill.get("lines").get(0);
        assertThat(line.get("name").asString()).isEqualTo("Burger");
        assertThat(line.get("qty").asInt()).isEqualTo(2);
        assertThat(line.get("modifiers").get(0).asString()).isEqualTo("Cheddar");
        assertThat(line.get("amountCents").asLong()).isEqualTo(3200);
    }

    @Test
    void aGuestPaysTheServerComputedBalancePlusTipThroughStripe() {
        UUID check = acceptedCheck();
        UUID key = UUID.randomUUID();

        HttpResponse<String> r = d.guestPay(qr, check, key, 500);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(body(r).get("clientSecret").asString()).endsWith("_secret_x");
        assertThat(body(r).get("publishableKey").asString()).isEqualTo("pk_test_it");
        FakeStripe.Request intent = STRIPE.drain().stream().filter(x -> x.path().equals("/v1/payment_intents"))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(intent.formValue("amount")).isEqualTo("3932"); // 3432 + 500 tip
        Map<String, Object> row = p.payment(key);
        assertThat(row.get("method")).isEqualTo("QR_CNP");
        assertThat(n(row.get("tip_cents"))).isEqualTo(500);
        assertThat(n(row.get("application_fee_cents"))).isEqualTo(144); // 2.90% of 3932 = 114 + 30 fixed

        assertThat(d.guestPay(qr, check, key, 500).statusCode()).isEqualTo(200); // a retry
        HttpResponse<String> second = d.guestPay(qr, check, UUID.randomUUID(), 0);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(body(second).get("error").asString()).isEqualTo("NOTHING_DUE");
    }

    @Test
    void anOrderStaffHaveNotAcceptedCannotBePaid() {
        UUID check = d.checkOf(order(1));

        HttpResponse<String> r = d.guestPay(qr, check, UUID.randomUUID(), 0);

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(body(r).get("error").asString()).isEqualTo("ORDER_NOT_ACCEPTED");
        assertThat(d.count("select count(*) from payment where check_id = ?", check)).isZero();
    }

    @Test
    void anotherTablesCheckIsNotFound() {
        UUID check = acceptedCheck();
        String otherTable = d.table(loc, "T8");

        assertThat(d.guestCheck(otherTable, check).statusCode()).isEqualTo(404);
        assertThat(d.guestPay(otherTable, check, UUID.randomUUID(), 0).statusCode()).isEqualTo(404);
        assertThat(d.guestPay(qr, check, UUID.randomUUID(), -1).statusCode()).isEqualTo(400);
    }
}
