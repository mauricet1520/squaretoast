package com.squaretoast.guest;

import static com.squaretoast.testsupport.DeviceFixture.body;
import static com.squaretoast.testsupport.DeviceFixture.guestOrderBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.DeviceFixture;
import com.squaretoast.testsupport.DeviceFixture.Device;
import com.squaretoast.testsupport.DeviceFixture.GuestLine;
import com.squaretoast.testsupport.MenuFixture;
import com.squaretoast.testsupport.PaymentFixture;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/** Slice 12: staff see guest QR orders on the POS, accept them, and open their checks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GuestOrdersIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    DeviceFixture d;
    MenuFixture m;
    UUID loc;
    UUID burger;
    UUID cheddar;
    String qr;
    Device pos;

    /** 7.25% food tax; burger $15, cheddar +$1. */
    @BeforeEach
    void seed() {
        d = new DeviceFixture(dataSource, port);
        m = new MenuFixture(dataSource, port);
        loc = new PaymentFixture(dataSource, port, "whsec_it").location(15, 5, 290, 30);
        d.taxRule("US", "FOOD", 72_500);
        burger = m.menuItem(loc, "Burger", 1500);
        UUID cheese = m.group(loc, "Cheese", 0, 1);
        m.link(burger, cheese);
        cheddar = m.option(cheese, "Cheddar", 100);
        qr = d.table(loc, "T7");
        pos = d.enrolledDevice(loc);
    }

    private UUID guestOrder(String table, int qty, UUID... options) {
        UUID order = UUID.randomUUID();
        HttpResponse<String> r = d.guestOrder(table, guestOrderBody(order, List.of(new GuestLine(burger, qty, List.of(options)))));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return order;
    }

    private JsonNode list(String token) {
        HttpResponse<String> r = d.raw("GET", "/api/v1/guest-orders", null, token);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return body(r).get("orders");
    }

    @Test
    void aWaitingGuestOrderShowsWhatWasOrderedAndHasNoCheckYet() {
        UUID order = guestOrder(qr, 2, cheddar);
        JsonNode orders = list(pos.token());
        assertThat(orders).hasSize(1);
        JsonNode o = orders.get(0);
        assertThat(o.get("orderId").asString()).isEqualTo(order.toString());
        assertThat(o.get("table").asString()).isEqualTo("T7");
        assertThat(o.get("status").asString()).isEqualTo("WAITING");
        assertThat(o.get("items").get(0).asString()).isEqualTo("2× Burger (Cheddar)");
        assertThat(o.get("checks")).isEmpty();
        assertThat(o.get("openedAt").asString()).isNotBlank();
    }

    @Test
    void anAcceptedGuestOrderListsItsCheckWithTheServersAmountDue() {
        UUID order = guestOrder(qr, 2, cheddar);
        assertThat(d.commit(pos.token(), order).statusCode()).isEqualTo(200);
        JsonNode o = list(pos.token()).get(0);
        assertThat(o.get("status").asString()).isEqualTo("ACCEPTED");
        JsonNode check = o.get("checks").get(0);
        assertThat(check.get("checkId").asString()).isEqualTo(d.checkOf(order).toString());
        assertThat(check.get("dueCents").asLong()).isEqualTo(3432); // 3200 + 7.25% tax
        assertThat(check.get("currency").asString()).isEqualTo("USD");
        assertThat(check.get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void posOrdersOtherLocationsAndOldOrdersAreLeftOut() {
        UUID old = guestOrder(qr, 1);
        m.exec("update orders set opened_at = now() - interval '13 hours' where id = ?", old);
        m.exec("update orders set origin_device_id = ? where id = ?", pos.id(), guestOrder(qr, 1)); // made on a POS
        UUID otherLoc = new PaymentFixture(dataSource, port, "whsec_it").location(15, 5, 290, 30);
        UUID otherBurger = m.menuItem(otherLoc, "Burger", 1500);
        d.guestOrder(d.table(otherLoc, "X1"), guestOrderBody(UUID.randomUUID(), List.of(new GuestLine(otherBurger, 1, List.of()))));
        assertThat(list(pos.token())).isEmpty();
    }

    @Test
    void newestFirst() {
        UUID first = guestOrder(qr, 1);
        m.exec("update orders set opened_at = now() - interval '1 hour' where id = ?", first);
        UUID second = guestOrder(qr, 1);
        JsonNode orders = list(pos.token());
        assertThat(orders.get(0).get("orderId").asString()).isEqualTo(second.toString());
        assertThat(orders.get(1).get("orderId").asString()).isEqualTo(first.toString());
    }

    @Test
    void needsADeviceToken() {
        assertThat(d.raw("GET", "/api/v1/guest-orders", null, null).statusCode()).isEqualTo(401);
    }
}
