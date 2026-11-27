package com.squaretoast.guest;

import static com.squaretoast.testsupport.DeviceFixture.guestOrderBody;
import static com.squaretoast.testsupport.PaymentFixture.chargeEvent;
import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.DeviceFixture;
import com.squaretoast.testsupport.DeviceFixture.Device;
import com.squaretoast.testsupport.DeviceFixture.GuestLine;
import com.squaretoast.testsupport.MenuFixture;
import com.squaretoast.testsupport.PaymentFixture;
import com.squaretoast.testsupport.SseClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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

/** Slice 13: the POS hears guest orders, accepts and guest payments live on its location's stream. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PosLiveIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void stripe(DynamicPropertyRegistry r) {
        r.add("squaretoast.stripe.webhook-secrets", () -> "whsec_it");
    }

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    DeviceFixture d;
    MenuFixture m;
    PaymentFixture p;
    UUID loc;
    UUID burger;
    String qr;
    Device pos;

    @BeforeEach
    void seed() {
        d = new DeviceFixture(dataSource, port);
        m = new MenuFixture(dataSource, port);
        p = new PaymentFixture(dataSource, port, "whsec_it");
        loc = p.location(15, 5, 290, 30);
        burger = m.menuItem(loc, "Burger", 1500);
        qr = d.table(loc, "T7");
        pos = d.enrolledDevice(loc);
    }

    private UUID guestOrder() {
        UUID order = UUID.randomUUID();
        assertThat(d.guestOrder(qr, guestOrderBody(order, List.of(new GuestLine(burger, 1, List.of())))).statusCode())
                .isEqualTo(200);
        return order;
    }

    private SseClient posStream() throws Exception {
        SseClient s = SseClient.open(d.kdsStreamUrl(d.streamTicket(pos.token()), null), null);
        assertThat(s.status()).isEqualTo(200);
        return s;
    }

    /** A guest QR payment row for the check, as the guest pay flow leaves it before Stripe confirms. */
    private String pendingGuestPayment(UUID check, long cents) {
        String intent = "pi_" + UUID.randomUUID().toString().replace("-", "");
        m.exec("""
                insert into payment(id, check_id, processor, processor_intent_ref, method, amount_cents, status, idempotency_key)
                values (?, ?, 'STRIPE', ?, 'QR_CNP', ?, 'PENDING', ?)""",
                UUID.randomUUID(), check, intent, cents, UUID.randomUUID());
        return intent;
    }

    @Test
    void thePosHearsANewGuestOrderAndItsAcceptance() throws Exception {
        try (SseClient stream = posStream()) {
            UUID order = guestOrder();
            SseClient.Event placed = stream.next(WAIT);
            assertThat(placed.name()).isEqualTo("order");
            assertThat(placed.data().get("orderId").asString()).isEqualTo(order.toString());

            assertThat(d.commit(pos.token(), order).statusCode()).isEqualTo(200);
            SseClient.Event accepted = stream.next(WAIT); // so other POS devices drop the Accept button
            assertThat(accepted.name()).isEqualTo("order");
            assertThat(accepted.data().get("orderId").asString()).isEqualTo(order.toString());
            assertThat(accepted.data().get("status").asString()).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void aSucceededGuestPaymentTellsThePosWhichCheckChangedOnlyOnce() throws Exception {
        UUID order = guestOrder();
        assertThat(d.commit(pos.token(), order).statusCode()).isEqualTo(200);
        UUID check = d.checkOf(order);
        String intent = pendingGuestPayment(check, 1500);
        try (SseClient stream = posStream()) {
            String event = chargeEvent("evt_" + UUID.randomUUID(), "charge.succeeded", intent, 1500, null);
            assertThat(p.postWebhook(event).statusCode()).isEqualTo(200);
            SseClient.Event paid = stream.next(WAIT);
            assertThat(paid.name()).isEqualTo("check");
            assertThat(paid.data().get("checkId").asString()).isEqualTo(check.toString());
            assertThat(paid.data().size()).isEqualTo(1); // the id only: no amounts, no card details

            String again = chargeEvent("evt_" + UUID.randomUUID(), "charge.updated", intent, 1500, null);
            assertThat(p.postWebhook(again).statusCode()).isEqualTo(200);
            assertThat(stream.poll(Duration.ofMillis(500))).isNull();
        }
    }

    @Test
    void thePublicStreamNeverCarriesCheckEvents() throws Exception {
        UUID order = guestOrder();
        assertThat(d.commit(pos.token(), order).statusCode()).isEqualTo(200);
        String intent = pendingGuestPayment(d.checkOf(order), 1500);
        try (SseClient pub = SseClient.connect(m.baseUrl(), loc, null)) {
            assertThat(p.postWebhook(chargeEvent("evt_" + UUID.randomUUID(), "charge.succeeded", intent, 1500, null))
                    .statusCode()).isEqualTo(200);
            assertThat(pub.poll(Duration.ofMillis(500))).isNull();
        }
    }
}
