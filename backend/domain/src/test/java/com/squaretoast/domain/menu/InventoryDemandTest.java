package com.squaretoast.domain.menu;

import static com.squaretoast.domain.menu.MenuFixtures.group;
import static com.squaretoast.domain.menu.MenuFixtures.item;
import static com.squaretoast.domain.menu.MenuFixtures.option;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Total inventory an order consumes at commit, in one fixed lock order. */
class InventoryDemandTest {

    private final UUID bun = UUID.randomUUID();
    private final UUID patty = UUID.randomUUID();
    private final UUID avocado = UUID.randomUUID();
    private final ModifierOption addAvocado = option("Avocado", 150, new Consumption(avocado, 1));
    private final ModifierOption doublePatty = option("Double", 400, new Consumption(patty, 1));
    private final ModifierGroup addOns = group("Add-ons", 0, 2, addAvocado, doublePatty);
    private final MenuItem burger = item("Burger", 1200, List.of(addOns), new Consumption(bun, 1), new Consumption(patty, 1));
    private final MenuItem salad = item("Salad", 1000, List.of(addOns));

    @Test
    void multipliesItemAndOptionConsumptionByLineQuantity() {
        SortedMap<UUID, Long> demand = InventoryDemand.of(List.of(
                new LineDemand(burger, 3, List.of(addAvocado.id(), doublePatty.id()))));
        assertThat(demand).containsEntry(bun, 3L).containsEntry(patty, 6L).containsEntry(avocado, 3L).hasSize(3);
    }

    @Test
    void sumsSharedInventoryAcrossLinesAndLinkedGroups() {
        SortedMap<UUID, Long> demand = InventoryDemand.of(List.of(
                new LineDemand(burger, 1, List.of(addAvocado.id())),
                new LineDemand(salad, 2, List.of(addAvocado.id()))));
        assertThat(demand).containsEntry(avocado, 3L).containsEntry(bun, 1L).containsEntry(patty, 1L);
    }

    @Test
    void lockOrderIsIndependentOfLineOrder() {
        List<LineDemand> lines = new ArrayList<>(List.of(
                new LineDemand(salad, 1, List.of(addAvocado.id())),
                new LineDemand(burger, 1, List.of())));
        List<UUID> forward = List.copyOf(InventoryDemand.of(lines).keySet());
        List<UUID> reversed = List.copyOf(InventoryDemand.of(lines.reversed()).keySet());
        assertThat(forward).isEqualTo(reversed).isSorted();
    }

    @Test
    void optionNotOfferedOnTheItemIsRejected() {
        ModifierOption stray = option("Stray", 0, new Consumption(bun, 1));
        assertThatThrownBy(() -> InventoryDemand.of(List.of(new LineDemand(burger, 1, List.of(stray.id())))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowIsDetectedNotWrapped() {
        MenuItem bulk = item("Bulk", 1, List.of(), new Consumption(bun, Integer.MAX_VALUE));
        List<LineDemand> lines = List.of(
                new LineDemand(bulk, Integer.MAX_VALUE, List.of()),
                new LineDemand(bulk, Integer.MAX_VALUE, List.of()),
                new LineDemand(bulk, Integer.MAX_VALUE, List.of()));
        assertThatThrownBy(() -> InventoryDemand.of(lines)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void lineQuantityMustBePositive() {
        assertThatThrownBy(() -> new LineDemand(burger, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
