package com.squaretoast.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.MenuFixture;
import com.squaretoast.testsupport.SseClient;
import com.squaretoast.testsupport.SseClient.Event;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

/** Slice 2: availability changes reach SSE subscribers in < 100 ms and replay from Last-Event-ID. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SseRealtimeIT {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final long BUDGET_NANOS = Duration.ofMillis(100).toNanos();

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    MenuFixture f;
    UUID loc;
    UUID bun;
    UUID avocado;
    UUID slider;
    UUID burger;
    UUID salad;
    UUID addAvocado;

    @BeforeEach
    void menu() {
        f = new MenuFixture(dataSource, port);
        loc = f.location();
        bun = f.trackedInventory(loc, "Bun", 100);
        avocado = f.trackedInventory(loc, "Avocado", 100);
        slider = f.menuItem(loc, "Slider", 600);
        burger = f.menuItem(loc, "Burger", 1200);
        salad = f.menuItem(loc, "Salad", 1000);
        f.itemConsumes(slider, bun, 1);
        f.itemConsumes(burger, bun, 1);
        UUID addOns = f.group(loc, "Add-ons", 0, 1);
        addAvocado = f.option(addOns, "Avocado", 150);
        f.optionConsumes(addAvocado, avocado, 1);
        f.link(burger, addOns);
        f.link(salad, addOns);
    }

    @Test
    void streamIsServedAsEventStream() throws Exception {
        try (SseClient sse = SseClient.connect(f.baseUrl(), loc, null)) {
            assertThat(sse.status()).isEqualTo(200);
        }
    }

    @Test
    void commitReachesSubscribersWithinOneHundredMilliseconds() throws Exception {
        try (SseClient sse = SseClient.connect(f.baseUrl(), loc, null)) {
            for (int i = 0; i < 3; i++) { // warm-up: JIT, pools, prepared statements
                f.commit(f.order(loc, slider, 1));
                sse.next(WAIT);
            }
            List<Long> latencies = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                UUID order = f.order(loc, slider, 1);
                long sent = System.nanoTime();
                assertThat(f.commit(order).statusCode()).isEqualTo(200);
                Event e = sse.next(WAIT);
                assertThat(inventory(e, bun).get("onHand").asInt()).isEqualTo(100 - 3 - (i + 1));
                latencies.add(e.receivedNanos() - sent);
            }
            // Measured from before the commit request is sent, so this bounds commit -> client from above.
            assertThat(latencies).allSatisfy(ns -> assertThat(ns).isLessThan(BUDGET_NANOS));
        }
    }

    @Test
    void sellingTheLastUnitBroadcastsAutoEightySixForEveryConsumer() throws Exception {
        f.exec("update inventory_item set on_hand = 1 where id = ?", bun);
        try (SseClient sse = SseClient.connect(f.baseUrl(), loc, null)) {
            f.commit(f.order(loc, slider, 1));

            Event e = sse.next(WAIT);

            assertThat(e.name()).isEqualTo("availability");
            assertThat(inventory(e, bun).get("onHand").asInt()).isZero();
            assertThat(availability(e, "menuItems", slider)).isFalse();
            assertThat(availability(e, "menuItems", burger)).isFalse();
        }
    }

    @Test
    void manualEightySixOfASharedOptionIsBroadcastOnce() throws Exception {
        try (SseClient sse = SseClient.connect(f.baseUrl(), loc, null)) {
            f.eightySix(loc, avocado, true);

            Event e = sse.next(WAIT);

            assertThat(inventory(e, avocado).get("eightySixed").asBoolean()).isTrue();
            assertThat(availability(e, "modifierOptions", addAvocado)).isFalse();
            assertThat(e.data().get("modifierOptions")).hasSize(1);
            // The option group is optional, so the items themselves stay available.
            assertThat(availability(e, "menuItems", burger)).isTrue();
            assertThat(availability(e, "menuItems", salad)).isTrue();
        }
    }

    @Test
    void reconnectWithLastEventIdReplaysMissedEventsInOrderThenGoesLive() throws Exception {
        long seen;
        try (SseClient sse = SseClient.connect(f.baseUrl(), loc, null)) {
            f.commit(f.order(loc, slider, 1));
            seen = sse.next(WAIT).id();
        }
        f.commit(f.order(loc, slider, 1));
        f.eightySix(loc, avocado, true);
        f.commit(f.order(loc, slider, 1));

        try (SseClient sse = SseClient.connect(f.baseUrl(), loc, seen)) {
            List<Event> replayed = List.of(sse.next(WAIT), sse.next(WAIT), sse.next(WAIT));
            f.commit(f.order(loc, slider, 1));
            Event live = sse.next(WAIT);

            assertThat(replayed).extracting(Event::id).isSorted().allSatisfy(id -> assertThat(id).isGreaterThan(seen));
            assertThat(inventory(replayed.get(0), bun).get("onHand").asInt()).isEqualTo(98);
            assertThat(inventory(replayed.get(1), avocado).get("eightySixed").asBoolean()).isTrue();
            assertThat(inventory(replayed.get(2), bun).get("onHand").asInt()).isEqualTo(97);
            assertThat(live.id()).isGreaterThan(replayed.get(2).id());
            assertThat(inventory(live, bun).get("onHand").asInt()).isEqualTo(96);
            assertThat(sse.poll(Duration.ofMillis(300))).as("no duplicates").isNull();
        }
    }

    @Test
    void eventsAreScopedToTheirLocation() throws Exception {
        UUID otherLoc = f.location();
        UUID otherBun = f.trackedInventory(otherLoc, "Bun", 5);
        UUID otherSlider = f.menuItem(otherLoc, "Slider", 600);
        f.itemConsumes(otherSlider, otherBun, 1);

        try (SseClient other = SseClient.connect(f.baseUrl(), otherLoc, null)) {
            f.commit(f.order(loc, slider, 1));
            f.commit(f.order(otherLoc, otherSlider, 1));

            Event first = other.next(WAIT);

            assertThat(inventory(first, otherBun).get("onHand").asInt()).isEqualTo(4);
            assertThat(first.data().get("inventory")).hasSize(1);
        }
    }

    /**
     * Audit finding: the payload was built from a menu read before the location lock, so a change committed in
     * between was overwritten on clients by a later event claiming the old state. The test holds the location's
     * event lock (same key as RealtimeEventStore), lets a commit queue on it, changes stock, then releases it.
     */
    @Test
    void broadcastReflectsInventoryAsOfPublishNotAStaleSnapshot() throws Exception {
        UUID patty = f.trackedInventory(loc, "Patty", 5);
        f.itemConsumes(burger, patty, 1);
        UUID order = f.order(loc, slider, 1);

        try (SseClient sse = SseClient.connect(f.baseUrl(), loc, null); Connection holder = dataSource.getConnection()) {
            locationLock(holder, "pg_advisory_lock");
            CompletableFuture<Integer> commit;
            try {
                commit = CompletableFuture.supplyAsync(() -> f.commit(order).statusCode());
                awaitAdvisoryWaiter();
                f.exec("update inventory_item set on_hand = 0 where id = ?", patty);
            } finally {
                locationLock(holder, "pg_advisory_unlock");
            }

            assertThat(commit.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            Event e = sse.next(WAIT);
            assertThat(inventory(e, bun).get("onHand").asInt()).isEqualTo(99);
            assertThat(availability(e, "menuItems", burger)).as("patty is gone, so burger is 86'd").isFalse();
        }
    }

    private void locationLock(Connection c, String fn) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("select " + fn + "(hashtextextended(cast(? as text), 0))")) {
            ps.setString(1, loc.toString());
            ps.executeQuery().close();
        }
    }

    private void awaitAdvisoryWaiter() throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select count(*) from pg_locks where locktype = 'advisory' and not granted")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("commit never queued on the location lock");
    }

    @Test
    void failedCommitBroadcastsNothing() throws Exception {
        f.exec("update inventory_item set on_hand = 0 where id = ?", bun);
        try (SseClient sse = SseClient.connect(f.baseUrl(), loc, null)) {
            assertThat(f.commit(f.order(loc, slider, 1)).statusCode()).isEqualTo(409);
            assertThat(sse.poll(Duration.ofMillis(300))).isNull();
        }
    }

    private static JsonNode inventory(Event e, UUID id) {
        for (JsonNode n : e.data().get("inventory")) {
            if (n.get("id").asString().equals(id.toString())) {
                return n;
            }
        }
        throw new AssertionError("event " + e.id() + " has no inventory " + id + ": " + e.data());
    }

    private static boolean availability(Event e, String field, UUID id) {
        for (JsonNode n : e.data().get(field)) {
            if (n.get("id").asString().equals(id.toString())) {
                return n.get("available").asBoolean();
            }
        }
        throw new AssertionError("event " + e.id() + " has no " + field + " entry " + id + ": " + e.data());
    }
}
