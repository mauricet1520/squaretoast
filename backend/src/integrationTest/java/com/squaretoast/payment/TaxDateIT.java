package com.squaretoast.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.DeviceFixture;
import com.squaretoast.testsupport.DeviceFixture.Device;
import com.squaretoast.testsupport.DeviceFixture.Line;
import com.squaretoast.testsupport.MenuFixture;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plan 19.6: a check is taxed by the date it was sold, not the day it syncs or is paid. Its own database, so the rate
 * change it sets up ("5% until today, 10% from today") touches no other test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TaxDateIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    DeviceFixture d;
    MenuFixture m;
    UUID loc;
    UUID fries;
    Device pos;
    LocalDate today;

    @BeforeEach
    void seed() {
        d = new DeviceFixture(dataSource, port);
        m = new MenuFixture(dataSource, port);
        loc = m.location(); // state US, America/New_York
        fries = m.menuItem(loc, "Fries", 1000);
        pos = d.enrolledDevice(loc);
        today = LocalDate.now(NY);
        m.exec("delete from tax_rule where state_code = 'US'");
        m.exec("""
                insert into tax_rule(id, state_code, jurisdiction_code, tax_category, rate_ppm, effective_from, effective_to)
                values (?, 'US', 'US-STATE', 'FOOD', 50000, date '2020-01-01', ?), (?, 'US', 'US-STATE', 'FOOD', 100000, ?, null)""",
                UUID.randomUUID(), today, UUID.randomUUID(), today);
    }

    /** One $10 fries order on a POS check, rung up at {@code placedAt} (null: an older POS that does not send it). */
    private UUID sync(@Nullable Instant placedAt) {
        UUID check = UUID.randomUUID();
        ObjectNode mutation = d.orderPlaced(UUID.randomUUID(), UUID.randomUUID(), loc, Map.of(1, UUID.randomUUID()),
                List.of(new Line(fries, 1, 1, 1, 1000)), check);
        if (placedAt != null) {
            ObjectNode payload = (ObjectNode) DeviceFixture.JSON.readTree(mutation.get("payloadJson").asString());
            payload.put("placedAt", placedAt.toEpochMilli());
            mutation.put("payloadJson", payload.toString());
        }
        assertThat(DeviceFixture.results(d.sync(pos.token(), List.of(mutation))).values()).allMatch(v -> v.get(0).equals("APPLIED"));
        return check;
    }

    private long tax(UUID check) {
        return DeviceFixture.body(d.bill(pos.token(), check)).get("taxCents").asLong();
    }

    @Test
    void anOrderRungUpYesterdayIsTaxedAtYesterdaysRate() {
        assertThat(tax(sync(Instant.now().minus(Duration.ofHours(24))))).isEqualTo(50);
    }

    @Test
    void anOrderRungUpTodayIsTaxedAtTodaysRate() {
        assertThat(tax(sync(Instant.now()))).isEqualTo(100);
    }

    @Test
    void withoutPlacedAtTheOrderIsTaxedByWhenItReachedTheServer() {
        assertThat(tax(sync(null))).isEqualTo(100);
    }

    @Test
    void aDeviceClockInTheFutureCountsAsNow() {
        assertThat(tax(sync(Instant.now().plus(Duration.ofDays(5))))).isEqualTo(100);
    }

    @Test
    void aSaleTimeOlderThanSeventyTwoHoursIsClamped() {
        UUID check = sync(Instant.now().minus(Duration.ofDays(10)));
        assertThat(tax(check)).isEqualTo(50);
        assertThat(String.valueOf(d.row("select o.sold_on from orders o join guest_check g on g.order_id = o.id where g.id = ?", check)
                .get("sold_on"))).isEqualTo(LocalDate.now(NY).minusDays(3).toString());
    }

    @Test
    void aClaimSignedBeforeAMidnightRateChangeReconcilesWhenItSyncsAfter() {
        UUID check = UUID.randomUUID();
        // A paid claim for $10 + 5% = $10.50, signed yesterday at yesterday's rate.
        m.exec("""
                insert into offline_check_claim(check_id, device_id, location_id, claim_sig, payload, expires_at, amount_cents,
                                                currency, status)
                values (?, ?, ?, ?, ?, now() + interval '1 hour', 1050, 'USD', 'PAID')""",
                check, pos.id(), loc, new byte[] {1}, new byte[] {1});
        ObjectNode mutation = d.orderPlaced(UUID.randomUUID(), UUID.randomUUID(), loc, Map.of(1, UUID.randomUUID()),
                List.of(new Line(fries, 1, 1, 1, 1000)), check);
        ObjectNode payload = (ObjectNode) DeviceFixture.JSON.readTree(mutation.get("payloadJson").asString());
        payload.put("placedAt", Instant.now().minus(Duration.ofHours(24)).toEpochMilli());
        mutation.put("payloadJson", payload.toString());

        assertThat(DeviceFixture.results(d.sync(pos.token(), List.of(mutation))).values()).allMatch(v -> v.get(0).equals("APPLIED"));

        assertThat(d.row("select status from offline_check_claim where check_id = ?", check).get("status")).isEqualTo("RECONCILED");
    }
}
