package com.squaretoast.kds;

import static com.squaretoast.testsupport.DeviceFixture.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.DeviceFixture;
import com.squaretoast.testsupport.DeviceFixture.Device;
import com.squaretoast.testsupport.DeviceFixture.Line;
import com.squaretoast.testsupport.MenuFixture;
import com.squaretoast.testsupport.SseClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

/** Slice 6: the browser KDS reads a board, moves courses, and follows an authenticated stream. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KdsIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    DeviceFixture d;
    MenuFixture m;
    UUID loc;
    UUID burger;
    UUID fries;
    Device pos;
    Device kds;
    UUID order;
    UUID mains;
    UUID sides;

    @BeforeEach
    void seed() {
        d = new DeviceFixture(dataSource, port);
        m = new MenuFixture(dataSource, port);
        loc = m.location();
        burger = m.menuItem(loc, "Burger", 1500);
        fries = m.menuItem(loc, "Fries", 500);
        d.assignStation(burger, d.station(loc, "Grill"));
        d.table(loc, "T4");
        pos = d.enrolledDevice(loc);
        kds = d.enrolledDevice(loc);
        order = UUID.randomUUID();
        mains = UUID.randomUUID();
        sides = UUID.randomUUID();
        HttpResponse<String> r = d.sync(pos.token(), List.of(d.orderPlaced(UUID.randomUUID(), order, loc,
                Map.of(1, mains, 2, sides), List.of(new Line(burger, 1, 1, 2, 1500), new Line(fries, 1, 2, 1, 500)))));
        assertThat(DeviceFixture.results(r).values()).allMatch(v -> v.get(0).equals("APPLIED"));
        UUID burgerLine = d.row("select id from order_line where order_id = ? and menu_item_id = ?", order, burger)
                .values().stream().map(v -> (UUID) v).findFirst().orElseThrow();
        UUID noOnion = m.option(m.group(loc, "Toppings", 0, 3), "No onion", 0);
        m.exec("""
                insert into order_line_modifier(id, order_line_id, modifier_option_id, price_delta_cents, name_snapshot)
                values (?, ?, ?, 0, 'No onion')""", UUID.randomUUID(), burgerLine, noOnion);
    }

    @Test
    void theBoardShowsFiredCoursesWithTheirLinesStationAndTable() {
        assertThat(body(d.board(kds.token())).get("tickets")).isEmpty(); // held courses are not the kitchen's yet

        HttpResponse<String> fired = d.transition(kds.token(), mains, "FIRE", "HOLD");
        assertThat(fired.statusCode()).isEqualTo(200);
        assertThat(body(fired).get("state").asString()).isEqualTo("FIRED");

        JsonNode tickets = body(d.board(kds.token())).get("tickets");
        assertThat(tickets).hasSize(1);
        JsonNode t = tickets.get(0);
        assertThat(t.get("courseId").asString()).isEqualTo(mains.toString());
        assertThat(t.get("orderId").asString()).isEqualTo(order.toString());
        assertThat(t.get("table").asString()).isEqualTo("T4");
        assertThat(t.get("courseNo").asInt()).isEqualTo(1);
        assertThat(t.get("state").asString()).isEqualTo("FIRED");
        assertThat(t.get("lines")).hasSize(1);
        JsonNode line = t.get("lines").get(0);
        assertThat(line.get("name").asString()).isEqualTo("Burger");
        assertThat(line.get("qty").asInt()).isEqualTo(2);
        assertThat(line.get("station").asString()).isEqualTo("Grill");
        assertThat(line.get("modifiers").get(0).asString()).isEqualTo("No onion");
    }

    @Test
    void transitionsAreReversibleAndReachTheKdsStreamWithTheWholeTicket() throws Exception {
        try (SseClient stream = SseClient.open(d.kdsStreamUrl(d.streamTicket(kds.token()), null), null)) {
            assertThat(stream.status()).isEqualTo(200);

            assertThat(d.transition(kds.token(), mains, "FIRE", "HOLD").statusCode()).isEqualTo(200);
            SseClient.Event fired = stream.next(WAIT);
            assertThat(fired.name()).isEqualTo("course");
            assertThat(fired.data().get("courseId").asString()).isEqualTo(mains.toString());
            assertThat(fired.data().get("state").asString()).isEqualTo("FIRED");
            assertThat(fired.data().get("lines").get(0).get("name").asString()).isEqualTo("Burger");

            assertThat(d.transition(kds.token(), mains, "BUMP", "FIRED").statusCode()).isEqualTo(200);
            assertThat(stream.next(WAIT).data().get("state").asString()).isEqualTo("READY");
            assertThat(d.transition(kds.token(), mains, "RECALL", "READY").statusCode()).isEqualTo(200);
            SseClient.Event recalled = stream.next(WAIT);
            assertThat(recalled.data().get("state").asString()).isEqualTo("FIRED");
            assertThat(recalled.id()).isGreaterThan(fired.id());
        }
        assertThat(d.count("select count(*) from course_transition where course_id = ?", mains)).isEqualTo(3);
    }

    @Test
    void aCourseFiredThroughSyncAlsoReachesTheKdsStream() throws Exception {
        try (SseClient stream = SseClient.open(d.kdsStreamUrl(d.streamTicket(kds.token()), null), null)) {
            d.sync(pos.token(), List.of(d.courseTransitioned(UUID.randomUUID(), order, sides, "FIRE", "HOLD", "FIRED")));
            SseClient.Event e = stream.next(WAIT);
            assertThat(e.name()).isEqualTo("course");
            assertThat(e.data().get("courseId").asString()).isEqualTo(sides.toString());
        }
    }

    @Test
    void theStreamResumesAfterTheLastEventTheClientSaw() throws Exception {
        long first;
        try (SseClient stream = SseClient.open(d.kdsStreamUrl(d.streamTicket(kds.token()), null), null)) {
            d.transition(kds.token(), mains, "FIRE", "HOLD");
            first = stream.next(WAIT).id();
        }
        d.transition(kds.token(), mains, "BUMP", "FIRED"); // while disconnected
        try (SseClient stream = SseClient.open(d.kdsStreamUrl(d.streamTicket(kds.token()), first), null)) {
            SseClient.Event missed = stream.next(WAIT);
            assertThat(missed.data().get("state").asString()).isEqualTo("READY");
        }
    }

    @Test
    void aStaleOrIllegalTransitionIsRefused() {
        d.transition(kds.token(), mains, "FIRE", "HOLD");

        HttpResponse<String> stale = d.transition(kds.token(), mains, "FIRE", "HOLD");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(body(stale).get("error").asString()).isEqualTo("STALE_STATE");

        HttpResponse<String> illegal = d.transition(kds.token(), sides, "BUMP", "HOLD");
        assertThat(illegal.statusCode()).isEqualTo(409);
        assertThat(body(illegal).get("error").asString()).isEqualTo("ILLEGAL_TRANSITION");

        assertThat(d.transition(kds.token(), mains, "LAUNCH", "FIRED").statusCode()).isEqualTo(400);
    }

    @Test
    void anotherLocationsKitchenCannotSeeOrMoveTheCourse() {
        d.transition(kds.token(), mains, "FIRE", "HOLD");
        Device elsewhere = d.enrolledDevice(m.location());

        assertThat(d.transition(elsewhere.token(), mains, "BUMP", "FIRED").statusCode()).isEqualTo(404);
        assertThat(body(d.board(elsewhere.token())).get("tickets")).isEmpty();
        assertThat(d.count("select count(*) from course_transition where course_id = ?", mains)).isEqualTo(1);
    }

    @Test
    void streamTicketsWorkOnceAndExpire() throws Exception {
        String ticket = d.streamTicket(kds.token());
        try (SseClient ok = SseClient.open(d.kdsStreamUrl(ticket, null), null)) {
            assertThat(ok.status()).isEqualTo(200);
        }
        try (SseClient reused = SseClient.open(d.kdsStreamUrl(ticket, null), null)) {
            assertThat(reused.status()).isEqualTo(401);
        }

        String expired = "expired" + UUID.randomUUID().toString().replace("-", "");
        m.exec("""
                insert into kds_stream_ticket(ticket_hash, device_id, location_id, expires_at)
                values (?, ?, ?, now() - interval '1 second')""", DeviceFixture.sha256(expired), kds.id(), loc);
        try (SseClient late = SseClient.open(d.kdsStreamUrl(expired, null), null)) {
            assertThat(late.status()).isEqualTo(401);
        }
        try (SseClient bogus = SseClient.open(d.kdsStreamUrl("not-a-ticket", null), null)) {
            assertThat(bogus.status()).isEqualTo(401);
        }
    }

    @Test
    void aRevokedDevicesTicketNoLongerOpensTheStream() throws Exception {
        String ticket = d.streamTicket(kds.token());
        d.revoke(kds.id());
        try (SseClient s = SseClient.open(d.kdsStreamUrl(ticket, null), null)) {
            assertThat(s.status()).isEqualTo(401);
        }
    }

    @Test
    void thePublicStreamCarriesAvailabilityButNeverTickets() throws Exception {
        UUID buns = m.untrackedInventory(loc, "Buns");
        try (SseClient pub = SseClient.connect(m.baseUrl(), loc, null)) {
            d.transition(kds.token(), mains, "FIRE", "HOLD");
            assertThat(m.eightySix(loc, buns, true).statusCode()).isEqualTo(204);

            SseClient.Event first = pub.next(WAIT);
            assertThat(first.name()).isEqualTo("availability");
            assertThat(pub.poll(Duration.ofMillis(300))).isNull();
        }
    }

    @Test
    void revokingAKitchenScreenEndsItsOpenStream() throws Exception {
        Device doomed = d.enrolledDevice(loc);
        try (SseClient mine = SseClient.open(d.kdsStreamUrl(d.streamTicket(doomed.token()), null), null);
             SseClient other = SseClient.open(d.kdsStreamUrl(d.streamTicket(kds.token()), null), null)) {
            assertThat(mine.status()).isEqualTo(200);

            d.revoke(doomed.id());

            assertThat(mine.endsWithin(Duration.ofSeconds(20))).as("revoked device's stream closed").isTrue();
            assertThat(other.endsWithin(Duration.ofMillis(100))).as("another device's stream stays open").isFalse();
        }
    }

    @Test
    void kdsEndpointsNeedADeviceToken() {
        assertThat(d.board(null).statusCode()).isEqualTo(401);
        assertThat(d.streamTicketResponse(null).statusCode()).isEqualTo(401);
        assertThat(d.transition(null, mains, "FIRE", "HOLD").statusCode()).isEqualTo(401);
    }
}
