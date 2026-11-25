package com.squaretoast.menu;

import static com.squaretoast.testsupport.MenuFixture.body;
import static com.squaretoast.testsupport.MenuFixture.menuItem;
import static com.squaretoast.testsupport.MenuFixture.menuOption;
import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.MenuFixture;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** Slice 2: linked modifiers, derived availability, atomic decrement at order commit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AtomicEightySixIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    MenuFixture f;
    UUID loc;
    UUID bun;
    UUID patty;
    UUID avocado;
    UUID lettuce;
    UUID burger;
    UUID slider;
    UUID salad;
    UUID addOns;
    UUID addAvocado;

    /** Burger and slider both use buns; one "Add-ons" group with Avocado is linked to burger AND salad. */
    @BeforeEach
    void menu() {
        f = new MenuFixture(dataSource, port);
        loc = f.location();
        bun = f.trackedInventory(loc, "Bun", 10);
        patty = f.trackedInventory(loc, "Patty", 10);
        avocado = f.trackedInventory(loc, "Avocado", 10);
        lettuce = f.untrackedInventory(loc, "Lettuce");
        burger = f.menuItem(loc, "Burger", 1200);
        slider = f.menuItem(loc, "Slider", 600);
        salad = f.menuItem(loc, "Salad", 1000);
        f.itemConsumes(burger, bun, 1);
        f.itemConsumes(burger, patty, 1);
        f.itemConsumes(slider, bun, 1);
        f.itemConsumes(salad, lettuce, 1);
        addOns = f.group(loc, "Add-ons", 0, 2);
        addAvocado = f.option(addOns, "Avocado", 150);
        f.optionConsumes(addAvocado, avocado, 1);
        f.link(burger, addOns);
        f.link(salad, addOns);
    }

    @Test
    void linkedModifierGroupIsServedUnderEveryItemThatSharesIt() {
        JsonNode menu = f.menu(loc);
        JsonNode burgerGroup = menuItem(menu, burger).get("modifierGroups").get(0);
        JsonNode saladGroup = menuItem(menu, salad).get("modifierGroups").get(0);

        assertThat(burgerGroup.get("id").asString()).isEqualTo(addOns.toString()).isEqualTo(saladGroup.get("id").asString());
        assertThat(burgerGroup.get("minSelect").asInt()).isZero();
        assertThat(burgerGroup.get("maxSelect").asInt()).isEqualTo(2);
        assertThat(menuOption(menu, burger, addAvocado).get("priceDeltaCents").asLong()).isEqualTo(150L);
        assertThat(menuItem(menu, burger).get("priceCents").asLong()).isEqualTo(1200L);
        assertThat(menuItem(menu, burger).get("currency").asString()).isEqualTo("USD");
        assertThat(menuItem(menu, slider).get("modifierGroups")).isEmpty();
    }

    @Test
    void eightySixingInventoryMakesEveryConsumerUnavailableAndBack() {
        assertThat(f.eightySix(loc, bun, true).statusCode()).isEqualTo(204);
        assertThat(f.eightySix(loc, avocado, true).statusCode()).isEqualTo(204);

        JsonNode menu = f.menu(loc);
        assertThat(menuItem(menu, burger).get("available").asBoolean()).isFalse();
        assertThat(menuItem(menu, slider).get("available").asBoolean()).isFalse();
        assertThat(menuItem(menu, salad).get("available").asBoolean()).isTrue();
        assertThat(menuOption(menu, burger, addAvocado).get("available").asBoolean()).isFalse();
        assertThat(menuOption(menu, salad, addAvocado).get("available").asBoolean()).isFalse();

        assertThat(f.eightySix(loc, bun, false).statusCode()).isEqualTo(204);
        assertThat(f.eightySix(loc, avocado, false).statusCode()).isEqualTo(204);
        menu = f.menu(loc);
        assertThat(menuItem(menu, burger).get("available").asBoolean()).isTrue();
        assertThat(menuOption(menu, salad, addAvocado).get("available").asBoolean()).isTrue();
    }

    @Test
    void eightySixingAnotherLocationsInventoryIsNotFound() {
        UUID other = f.location();
        assertThat(f.eightySix(other, bun, true).statusCode()).isEqualTo(404);
        assertThat(f.eightySix(loc, UUID.randomUUID(), true).statusCode()).isEqualTo(404);
    }

    @Test
    void requiredGroupWithNoAvailableOptionsMakesTheItemUnavailable() {
        UUID potato = f.trackedInventory(loc, "Potato", 0);
        UUID sides = f.group(loc, "Side", 1, 1);
        UUID fries = f.option(sides, "Fries", 0);
        f.optionConsumes(fries, potato, 1);
        f.link(slider, sides);

        JsonNode menu = f.menu(loc);
        assertThat(menuOption(menu, slider, fries).get("available").asBoolean()).isFalse();
        assertThat(menuItem(menu, slider).get("available").asBoolean()).isFalse();
    }

    @Test
    void commitDecrementsItemAndModifierInventoryTimesQuantity() {
        UUID order = f.order(loc, burger, 2, addAvocado);
        f.addLine(order, salad, 1, addAvocado);

        HttpResponse<String> r = f.commit(order);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(body(r).get("status").asString()).isEqualTo("COMMITTED");
        assertThat(f.status(order)).isEqualTo("COMMITTED");
        assertThat(f.onHand(bun)).isEqualTo(8);
        assertThat(f.onHand(patty)).isEqualTo(8);
        assertThat(f.onHand(avocado)).isEqualTo(7);
        assertThat(f.onHand(lettuce)).isNull();
    }

    @Test
    void sellingTheLastUnitAutoEightySixesEveryConsumer() {
        f.exec("update inventory_item set on_hand = 1 where id = ?", bun);
        assertThat(f.commit(f.order(loc, slider, 1)).statusCode()).isEqualTo(200);

        JsonNode menu = f.menu(loc);
        assertThat(f.onHand(bun)).isZero();
        assertThat(menuItem(menu, slider).get("available").asBoolean()).isFalse();
        assertThat(menuItem(menu, burger).get("available").asBoolean()).isFalse();
    }

    @Test
    void concurrentCommitsNeverOversell() throws Exception {
        f.exec("update inventory_item set on_hand = 3 where id = ?", bun);
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            orders.add(f.order(loc, i % 2 == 0 ? burger : slider, 1));
        }

        List<HttpResponse<String>> results = commitConcurrently(orders);

        long ok = results.stream().filter(r -> r.statusCode() == 200).count();
        List<HttpResponse<String>> conflicts = results.stream().filter(r -> r.statusCode() == 409).toList();
        assertThat(ok).isEqualTo(3);
        assertThat(conflicts).hasSize(5).allSatisfy(r -> {
            assertThat(body(r).get("conflict").asString()).isEqualTo("OVERSOLD");
            assertThat(body(r).get("inventoryItemId").asString()).isEqualTo(bun.toString());
        });
        assertThat(f.onHand(bun)).isZero();
        assertThat(orders.stream().filter(o -> f.status(o).equals("OPEN")).count()).isEqualTo(5);
    }

    @Test
    void concurrentCommitsTouchingInventoryInOppositeLineOrderDoNotDeadlock() throws Exception {
        f.exec("update inventory_item set on_hand = 1000 where id in (?, ?)", bun, avocado);
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID order = f.openOrder(loc);
            if (i % 2 == 0) {
                f.addLine(order, slider, 1);
                f.addLine(order, salad, 1, addAvocado);
            } else {
                f.addLine(order, salad, 1, addAvocado);
                f.addLine(order, slider, 1);
            }
            orders.add(order);
        }

        List<HttpResponse<String>> results = commitConcurrently(orders);

        assertThat(results).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(f.onHand(bun)).isEqualTo(980);
        assertThat(f.onHand(avocado)).isEqualTo(980);
    }

    @Test
    void oversoldOrderRollsBackEveryDecrementAndStaysOpen() {
        UUID cheese = f.trackedInventory(loc, "Cheese", 0);
        UUID melt = f.menuItem(loc, "Melt", 900);
        f.itemConsumes(melt, bun, 1);
        f.itemConsumes(melt, cheese, 1);
        UUID order = f.order(loc, melt, 1);

        HttpResponse<String> r = f.commit(order);

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(body(r).get("conflict").asString()).isEqualTo("OVERSOLD");
        assertThat(body(r).get("inventoryItemId").asString()).isEqualTo(cheese.toString());
        assertThat(f.onHand(bun)).isEqualTo(10);
        assertThat(f.status(order)).isEqualTo("OPEN");
    }

    @Test
    void manuallyEightySixedInventoryBlocksCommitEvenWhenUntracked() {
        f.eightySix(loc, lettuce, true);
        UUID order = f.order(loc, salad, 1);

        HttpResponse<String> r = f.commit(order);

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(body(r).get("conflict").asString()).isEqualTo("ITEM_86ED");
        assertThat(body(r).get("inventoryItemId").asString()).isEqualTo(lettuce.toString());
        assertThat(f.status(order)).isEqualTo("OPEN");
    }

    @Test
    void modifierLimitsAreEnforcedAtCommit() {
        UUID cheeseGroup = f.group(loc, "Cheese", 1, 1);
        UUID cheddar = f.option(cheeseGroup, "Cheddar", 100);
        UUID swiss = f.option(cheeseGroup, "Swiss", 100);
        f.link(burger, cheeseGroup);

        assertInvalid(f.order(loc, burger, 1), "TOO_FEW");
        assertInvalid(f.order(loc, burger, 1, cheddar, swiss), "TOO_MANY");
        assertInvalid(f.order(loc, slider, 1, addAvocado), "UNKNOWN_OPTION");
        assertThat(f.onHand(bun)).isEqualTo(10);
        assertThat(f.commit(f.order(loc, burger, 1, swiss, addAvocado)).statusCode()).isEqualTo(200);
    }

    @Test
    void itemFromAnotherLocationCannotBeCommitted() {
        UUID other = f.location();
        UUID order = f.order(other, burger, 1);

        HttpResponse<String> r = f.commit(order);

        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(body(r).get("error").asString()).isEqualTo("ITEM_NOT_AT_LOCATION");
        assertThat(f.onHand(bun)).isEqualTo(10);
    }

    @Test
    void deactivatedItemCannotBeCommitted() {
        UUID order = f.order(loc, slider, 1);
        f.exec("update menu_item set active = false where id = ?", slider);

        HttpResponse<String> r = f.commit(order);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
        assertThat(body(r).get("error").asString()).isEqualTo("ITEM_INACTIVE");
        assertThat(f.onHand(bun)).isEqualTo(10);
        assertThat(f.status(order)).isEqualTo("OPEN");
    }

    @Test
    void anOrderIsCommittedOnlyOnce() {
        UUID order = f.order(loc, slider, 1);
        assertThat(f.commit(order).statusCode()).isEqualTo(200);

        HttpResponse<String> again = f.commit(order);

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(body(again).get("conflict").asString()).isEqualTo("ORDER_NOT_OPEN");
        assertThat(f.onHand(bun)).isEqualTo(9);
        assertThat(f.commit(UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void aNegativeOrOversizedLineIsRefusedAtCommitNotAServerError() {
        UUID discount = f.option(addOns, "Staff discount", -2000); // takes a $6 slider below zero
        UUID negative = f.order(loc, slider, 1, discount);
        UUID tooMany = f.order(loc, salad, 1000);
        UUID tooLong = f.openOrder(loc);
        for (int i = 0; i < 201; i++) {
            f.addLine(tooLong, salad, 1);
        }

        for (UUID order : List.of(negative, tooMany, tooLong)) {
            HttpResponse<String> r = f.commit(order);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
            assertThat(body(r).get("error").asString()).isEqualTo("INVALID_LINE");
            assertThat(f.status(order)).isEqualTo("OPEN");
        }
        assertThat(f.onHand(bun)).isEqualTo(10);
        assertThat(f.commit(f.order(loc, salad, 999)).statusCode()).isEqualTo(200);
    }

    private void assertInvalid(UUID order, String reason) {
        HttpResponse<String> r = f.commit(order);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
        assertThat(body(r).get("error").asString()).isEqualTo("INVALID_MODIFIERS");
        assertThat(body(r).get("reason").asString()).isEqualTo(reason);
        assertThat(f.status(order)).isEqualTo("OPEN");
    }

    private List<HttpResponse<String>> commitConcurrently(List<UUID> orders) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (UUID o : orders) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return f.commit(o);
                }));
            }
            go.countDown();
            List<HttpResponse<String>> results = new ArrayList<>();
            for (Future<HttpResponse<String>> fut : futures) {
                results.add(fut.get());
            }
            return results;
        }
    }
}
