package com.squaretoast.sync;

import static com.squaretoast.testsupport.DeviceFixture.body;
import static com.squaretoast.testsupport.DeviceFixture.results;
import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.DeviceFixture;
import com.squaretoast.testsupport.DeviceFixture.Device;
import com.squaretoast.testsupport.DeviceFixture.Line;
import com.squaretoast.testsupport.MenuFixture;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
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
import tools.jackson.databind.node.ObjectNode;

/** Slice 5b: device enrollment and auth, and the POS outbox's server side. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DeviceSyncIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    DeviceFixture d;
    MenuFixture m;
    UUID loc;
    UUID burger;
    UUID patties;
    Device pos;

    @BeforeEach
    void seed() {
        d = new DeviceFixture(dataSource, port);
        m = new MenuFixture(dataSource, port);
        loc = m.location();
        burger = m.menuItem(loc, "Burger", 1500);
        patties = m.trackedInventory(loc, "Patty", 10);
        m.itemConsumes(burger, patties, 1);
        pos = d.enrolledDevice(loc);
    }

    private ObjectNode burgers(UUID mutation, UUID order, int qty, Map<Integer, UUID> courses) {
        return d.orderPlaced(mutation, order, loc, courses, List.of(
                new Line(burger, 1, 1, qty, 1), // the device claims 1 cent; the server must use the menu price
                new Line(burger, 2, 2, 1, 1)));
    }

    private static Map<Integer, UUID> twoCourses() {
        return Map.of(1, UUID.randomUUID(), 2, UUID.randomUUID());
    }

    // ------------------------------------------------------------------ enrollment

    @Test
    void enrollmentCodesWorkOnceAndTheTokenIsStoredOnlyAsAHash() {
        String code = d.enrollmentCode(loc, 15);
        String key = DeviceFixture.p256PublicKey();

        HttpResponse<String> r = d.enroll(code, key);

        assertThat(r.statusCode()).isEqualTo(200);
        String token = body(r).get("deviceToken").asString();
        UUID device = UUID.fromString(body(r).get("deviceId").asString());
        assertThat(body(r).get("locationId").asString()).isEqualTo(loc.toString());
        assertThat(token.length()).isGreaterThanOrEqualTo(43);
        Map<String, Object> row = d.row("select token_hash, public_key_spki, revoked_at from device where id = ?", device);
        assertThat((byte[]) row.get("token_hash")).isEqualTo(DeviceFixture.sha256(token));
        assertThat((byte[]) row.get("public_key_spki")).isEqualTo(java.util.Base64.getDecoder().decode(key));
        assertThat(row.get("revoked_at")).isNull();

        assertThat(d.enroll(code, DeviceFixture.p256PublicKey()).statusCode()).isEqualTo(400);
        assertThat(d.count("select count(*) from device where location_id = ?", loc)).isEqualTo(2); // pos + this one
    }

    @Test
    void badCodesAndKeysAreRejected() {
        assertThat(d.enroll(d.enrollmentCode(loc, -1), DeviceFixture.p256PublicKey()).statusCode()).isEqualTo(400);
        assertThat(d.enroll("ENR-unknown", DeviceFixture.p256PublicKey()).statusCode()).isEqualTo(400);
        assertThat(d.enroll(d.enrollmentCode(loc, 15), DeviceFixture.rsaPublicKey()).statusCode()).isEqualTo(400);
        assertThat(d.enroll(d.enrollmentCode(loc, 15), "not base64!").statusCode()).isEqualTo(400);
        assertThat(d.count("select count(*) from device where location_id = ?", loc)).isEqualTo(1);
    }

    @Test
    void aRejectedKeyDoesNotBurnTheCode() {
        String code = d.enrollmentCode(loc, 15);
        assertThat(d.enroll(code, DeviceFixture.rsaPublicKey()).statusCode()).isEqualTo(400);
        assertThat(d.enroll(code, DeviceFixture.p256PublicKey()).statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ auth

    @Test
    void everyDeviceEndpointRequiresALiveToken() {
        Device revoked = d.enrolledDevice(loc);
        d.revoke(revoked.id());
        UUID check = UUID.randomUUID();
        for (String token : Arrays.asList(null, "nope", revoked.token())) {
            assertThat(d.me(token).statusCode()).as("me %s", token).isEqualTo(401);
            assertThat(d.sync(token, List.of()).statusCode()).as("sync %s", token).isEqualTo(401);
            assertThat(d.connectionToken(token).statusCode()).as("token %s", token).isEqualTo(401);
            assertThat(d.pay(token, check, DeviceFixture.payBody(UUID.randomUUID(), "CARD_PRESENT", 0)).statusCode())
                    .as("pay %s", token).isEqualTo(401);
        }
    }

    @Test
    void encodedOrDecoratedPathsAreStillGuarded() {
        for (String path : List.of("/api/v1/%73ync/mutations", "/api/v1/sync;x=1/mutations", "/api/v1//sync/mutations",
                "/api/v1/devices/%6De")) {
            assertThat(d.raw(path.endsWith("me") ? "GET" : "POST", path, path.endsWith("me") ? null : "{\"mutations\":[]}", null)
                    .statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void apiPathsArePrivateUnlessListedAsPublic() {
        assertThat(d.raw("GET", "/api/v1/devices/anything-new", null, null).statusCode()).isEqualTo(401);
        assertThat(d.raw("GET", "/api/v1/health", null, null).statusCode()).isEqualTo(200);
        // Slice 6: the menu is private now; guests reach it through their table's QR route instead.
        assertThat(d.raw("GET", "/api/v1/locations/" + loc + "/menu", null, null).statusCode()).isEqualTo(401);
        assertThat(d.raw("GET", "/api/v1/t/" + d.table(loc, "T9"), null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void meDescribesTheDeviceAndItsTerminalLocation() {
        HttpResponse<String> r = d.me(pos.token());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(body(r).get("deviceId").asString()).isEqualTo(pos.id().toString());
        assertThat(body(r).get("locationId").asString()).isEqualTo(loc.toString());
        assertThat(body(r).get("terminalLocationId").isNull()).isTrue();

        d.terminalLocation(loc, "tml_Main1");
        assertThat(body(d.me(pos.token())).get("terminalLocationId").asString()).isEqualTo("tml_Main1");
    }

    // ------------------------------------------------------------------ ORDER_PLACED

    @Test
    void anOrderIsRepricedFromTheMenuGetsACheckAndTakesStockAtCommit() {
        UUID mutation = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        Map<Integer, UUID> courses = twoCourses();

        HttpResponse<String> r = d.sync(pos.token(), List.of(burgers(mutation, order, 2, courses)));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(results(r)).containsExactly(Map.entry(mutation.toString(), List.of("APPLIED", "")));
        Map<String, Object> o = d.row("select status, location_id, origin_device_id from orders where id = ?", order);
        assertThat(o.get("status")).isEqualTo("COMMITTED");
        assertThat(o.get("origin_device_id")).isEqualTo(pos.id());
        assertThat(d.count("select count(*) from order_line where order_id = ? and unit_price_cents = 1500", order)).isEqualTo(2);
        assertThat(d.count("select coalesce(sum(qty), 0) from order_line where order_id = ?", order)).isEqualTo(3);
        assertThat(d.count("select count(*) from seat where order_id = ?", order)).isEqualTo(2);
        assertThat(d.count("select count(*) from course where order_id = ? and state = 'HOLD' and id in (?, ?)",
                order, courses.get(1), courses.get(2))).isEqualTo(2);
        UUID check = d.checkOf(order);
        assertThat(d.count("""
                select count(*) from check_allocation a join order_line l on l.id = a.order_line_id
                where a.check_id = ? and a.numerator = 1 and a.denominator = 1""", check)).isEqualTo(2);
        assertThat(m.onHand(patties)).isEqualTo(7);
        assertThat(d.count("select count(*) from processed_client_mutation where mutation_id = ? and device_id = ? and result = 'APPLIED'",
                mutation, pos.id())).isEqualTo(1);
    }

    @Test
    void aResentMutationIsADuplicateAndChangesNothing() {
        UUID mutation = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        ObjectNode placed = burgers(mutation, order, 1, twoCourses());
        d.sync(pos.token(), List.of(placed));

        HttpResponse<String> again = d.sync(pos.token(), List.of(placed));

        assertThat(results(again)).containsExactly(Map.entry(mutation.toString(), List.of("DUPLICATE", "")));
        assertThat(m.onHand(patties)).isEqualTo(8);
        assertThat(d.count("select count(*) from order_line where order_id = ?", order)).isEqualTo(2);
    }

    @Test
    void ordersForAnotherLocationOrWithItsItemsConflict() {
        UUID otherLoc = m.location();
        UUID otherItem = m.menuItem(otherLoc, "Elsewhere", 900);
        UUID foreign = UUID.randomUUID();
        UUID stolenItem = UUID.randomUUID();
        UUID o1 = UUID.randomUUID();
        UUID o2 = UUID.randomUUID();
        Map<Integer, UUID> c = Map.of(1, UUID.randomUUID());

        HttpResponse<String> r = d.sync(pos.token(), List.of(
                d.orderPlaced(foreign, o1, otherLoc, c, List.of(new Line(otherItem, 1, 1, 1, 900))),
                d.orderPlaced(stolenItem, o2, loc, Map.of(1, UUID.randomUUID()), List.of(new Line(otherItem, 1, 1, 1, 900)))));

        assertThat(results(r)).containsExactly(
                Map.entry(foreign.toString(), List.of("CONFLICT", "FORBIDDEN_LOCATION")),
                Map.entry(stolenItem.toString(), List.of("CONFLICT", "ITEM_NOT_AT_LOCATION")));
        assertThat(d.count("select count(*) from orders where id in (?, ?)", o1, o2)).isZero();
    }

    @Test
    void anOversoldOrderConflictsWritesNothingAndKeepsItsReasonOnResend() {
        UUID mutation = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        ObjectNode tooMany = burgers(mutation, order, 10, twoCourses()); // 11 patties needed, 10 on hand

        HttpResponse<String> r = d.sync(pos.token(), List.of(tooMany));

        assertThat(results(r)).containsExactly(Map.entry(mutation.toString(), List.of("CONFLICT", "OVERSOLD")));
        assertThat(d.count("select count(*) from orders where id = ?", order)).isZero();
        assertThat(d.count("select count(*) from guest_check g join orders o on o.id = g.order_id where o.id = ?", order)).isZero();
        assertThat(m.onHand(patties)).isEqualTo(10);

        assertThat(results(d.sync(pos.token(), List.of(tooMany))))
                .containsExactly(Map.entry(mutation.toString(), List.of("CONFLICT", "OVERSOLD")));
    }

    @Test
    void oneConflictDoesNotStopTheRestOfTheBatch() {
        UUID ok1 = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        UUID ok2 = UUID.randomUUID();

        HttpResponse<String> r = d.sync(pos.token(), List.of(
                burgers(ok1, UUID.randomUUID(), 1, twoCourses()),
                burgers(bad, UUID.randomUUID(), 50, twoCourses()),
                burgers(ok2, UUID.randomUUID(), 1, twoCourses())));

        assertThat(results(r)).containsExactly(
                Map.entry(ok1.toString(), List.of("APPLIED", "")),
                Map.entry(bad.toString(), List.of("CONFLICT", "OVERSOLD")),
                Map.entry(ok2.toString(), List.of("APPLIED", "")));
        assertThat(m.onHand(patties)).isEqualTo(6);
    }

    @Test
    void aMutationIdOwnedByAnotherDeviceConflicts() {
        Device other = d.enrolledDevice(loc);
        UUID mutation = UUID.randomUUID();
        d.sync(pos.token(), List.of(burgers(mutation, UUID.randomUUID(), 1, twoCourses())));
        UUID order = UUID.randomUUID();

        HttpResponse<String> r = d.sync(other.token(), List.of(burgers(mutation, order, 1, twoCourses())));

        assertThat(results(r)).containsExactly(Map.entry(mutation.toString(), List.of("CONFLICT", "MUTATION_ID_TAKEN")));
        assertThat(d.count("select count(*) from orders where id = ?", order)).isZero();
    }

    // ------------------------------------------------------------------ COURSE_TRANSITIONED

    @Test
    void courseTransitionsApplyFromTheServersOwnState() {
        UUID order = UUID.randomUUID();
        Map<Integer, UUID> courses = twoCourses();
        d.sync(pos.token(), List.of(burgers(UUID.randomUUID(), order, 1, courses)));
        UUID course = courses.get(1);
        UUID fire = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        UUID illegal = UUID.randomUUID();

        HttpResponse<String> r = d.sync(pos.token(), List.of(
                d.courseTransitioned(fire, order, course, "FIRE", "HOLD", "FIRED"),
                d.courseTransitioned(stale, order, course, "UNFIRE", "READY", "FIRED"),
                d.courseTransitioned(illegal, order, course, "SERVE", "FIRED", "SERVED")));

        assertThat(results(r)).containsExactly(
                Map.entry(fire.toString(), List.of("APPLIED", "")),
                Map.entry(stale.toString(), List.of("CONFLICT", "STALE_STATE")),
                Map.entry(illegal.toString(), List.of("CONFLICT", "ILLEGAL_TRANSITION")));
        assertThat(d.row("select state from course where id = ?", course).get("state")).isEqualTo("FIRE");
        assertThat(d.count("select count(*) from course_transition where course_id = ? and from_state = 'HOLD' and to_state = 'FIRE'",
                course)).isEqualTo(1);
        assertThat(d.count("select count(*) from course_transition where course_id = ?", course)).isEqualTo(1);
    }

    @Test
    void aCourseAtAnotherLocationCannotBeTransitioned() {
        UUID otherLoc = m.location();
        Device otherPos = d.enrolledDevice(otherLoc);
        UUID order = UUID.randomUUID();
        Map<Integer, UUID> courses = twoCourses();
        d.sync(pos.token(), List.of(burgers(UUID.randomUUID(), order, 1, courses)));
        UUID mutation = UUID.randomUUID();

        HttpResponse<String> r = d.sync(otherPos.token(),
                List.of(d.courseTransitioned(mutation, order, courses.get(1), "FIRE", "HOLD", "FIRED")));

        assertThat(results(r)).containsExactly(Map.entry(mutation.toString(), List.of("CONFLICT", "FORBIDDEN_LOCATION")));
        assertThat(d.row("select state from course where id = ?", courses.get(1)).get("state")).isEqualTo("HOLD");
    }

    // ------------------------------------------------------------------ batch validation

    @Test
    void unknownOrMalformedMutationsConflict() {
        UUID unknown = UUID.randomUUID();
        UUID garbage = UUID.randomUUID();
        UUID agg = UUID.randomUUID();

        HttpResponse<String> r = d.sync(pos.token(), List.of(
                d.mutation(unknown, agg, "TABLE_FLIPPED", "{}"),
                d.mutation(garbage, agg, "ORDER_PLACED", "not json")));

        assertThat(results(r)).containsExactly(
                Map.entry(unknown.toString(), List.of("CONFLICT", "INVALID_MUTATION")),
                Map.entry(garbage.toString(), List.of("CONFLICT", "INVALID_MUTATION")));
    }

    @Test
    void batchesAreCappedAtFifty() {
        List<ObjectNode> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(d.mutation(UUID.randomUUID(), UUID.randomUUID(), "TABLE_FLIPPED", "{}"));
        }
        assertThat(d.sync(pos.token(), many).statusCode()).isEqualTo(400);
        assertThat(d.count("select count(*) from processed_client_mutation where device_id = ?", pos.id())).isZero();

        HttpResponse<String> empty = d.sync(pos.token(), List.of());
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(body(empty).get("results").size()).isZero();
    }
}
