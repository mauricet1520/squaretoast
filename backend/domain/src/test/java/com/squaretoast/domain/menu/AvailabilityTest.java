package com.squaretoast.domain.menu;

import static com.squaretoast.domain.menu.MenuFixtures.eightySixed;
import static com.squaretoast.domain.menu.MenuFixtures.group;
import static com.squaretoast.domain.menu.MenuFixtures.id;
import static com.squaretoast.domain.menu.MenuFixtures.item;
import static com.squaretoast.domain.menu.MenuFixtures.option;
import static com.squaretoast.domain.menu.MenuFixtures.tracked;
import static com.squaretoast.domain.menu.MenuFixtures.untracked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.money.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Availability is derived from inventory, never stored on the menu row (plan §4). */
class AvailabilityTest {

    private final UUID bun = id();
    private final UUID avocado = id();

    @Test
    void untrackedInventoryIsAlwaysAvailableUnlessEightySixed() {
        MenuItem burger = item("Burger", 1200, List.of(), new Consumption(bun, 1));
        assertThat(Availability.of(Map.of(bun, untracked(bun))).item(burger)).isTrue();
        assertThat(Availability.of(Map.of(bun, eightySixed(bun))).item(burger)).isFalse();
    }

    @Test
    void trackedInventoryMustCoverTheConsumedQuantity() {
        MenuItem doubleBun = item("Double", 1500, List.of(), new Consumption(bun, 2));
        assertThat(Availability.of(Map.of(bun, tracked(bun, 2))).item(doubleBun)).isTrue();
        assertThat(Availability.of(Map.of(bun, tracked(bun, 1))).item(doubleBun)).isFalse();
        assertThat(Availability.of(Map.of(bun, tracked(bun, 0))).item(doubleBun)).isFalse();
    }

    @Test
    void manualEightySixWinsOverStockOnHand() {
        MenuItem burger = item("Burger", 1200, List.of(), new Consumption(bun, 1));
        InventoryLevel stockedBut86 = new InventoryLevel(bun, true, 50, true);
        assertThat(Availability.of(Map.of(bun, stockedBut86)).item(burger)).isFalse();
    }

    @Test
    void eightySixedOptionIsUnavailableUnderEveryItemThatSharesItsGroup() {
        ModifierOption addAvocado = option("Avocado", 150, new Consumption(avocado, 1));
        ModifierGroup addOns = group("Add-ons", 0, 2, addAvocado);
        MenuItem burger = item("Burger", 1200, List.of(addOns), new Consumption(bun, 1));
        MenuItem salad = item("Salad", 1000, List.of(addOns));

        Availability a = Availability.of(Map.of(bun, untracked(bun), avocado, tracked(avocado, 0)));

        assertThat(a.option(addAvocado)).isFalse();
        assertThat(burger.modifierGroups().getFirst()).isSameAs(salad.modifierGroups().getFirst());
        // The group is optional, so both items stay orderable without the option.
        assertThat(a.item(burger)).isTrue();
        assertThat(a.item(salad)).isTrue();
    }

    @Test
    void requiredGroupWithNoAvailableOptionMakesTheItemUnavailable() {
        UUID potato = id();
        UUID greens = id();
        ModifierOption fries = option("Fries", 0, new Consumption(potato, 1));
        ModifierOption side = option("Side salad", 0, new Consumption(greens, 1));
        ModifierGroup sides = group("Side", 1, 1, fries, side);
        MenuItem burger = item("Burger", 1200, List.of(sides));

        assertThat(Availability.of(Map.of(potato, tracked(potato, 0), greens, tracked(greens, 3))).item(burger)).isTrue();
        assertThat(Availability.of(Map.of(potato, tracked(potato, 0), greens, eightySixed(greens))).item(burger)).isFalse();
    }

    @Test
    void requiredGroupNeedsAsManyAvailableOptionsAsItsMinimum() {
        UUID a = id();
        UUID b = id();
        ModifierGroup pickTwo = group("Pick two", 2, 2, option("A", 0, new Consumption(a, 1)), option("B", 0, new Consumption(b, 1)));
        MenuItem combo = item("Combo", 900, List.of(pickTwo));

        assertThat(Availability.of(Map.of(a, untracked(a), b, untracked(b))).item(combo)).isTrue();
        assertThat(Availability.of(Map.of(a, untracked(a), b, eightySixed(b))).item(combo)).isFalse();
    }

    @Test
    void inactiveItemsAndOptionsAreUnavailable() {
        MenuItem retired = new MenuItem(id(), "Retired", Money.ofCents(100, MenuFixtures.USD), false, List.of(), List.of());
        ModifierOption gone = new ModifierOption(id(), "Gone", 0, false, List.of());
        Availability a = Availability.of(Map.of());
        assertThat(a.item(retired)).isFalse();
        assertThat(a.option(gone)).isFalse();
    }

    @Test
    void consumptionOfUnknownInventoryIsADataError() {
        MenuItem burger = item("Burger", 1200, List.of(), new Consumption(bun, 1));
        assertThatThrownBy(() -> Availability.of(Map.of()).item(burger))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valueObjectsRejectImpossibleState() {
        assertThatThrownBy(() -> new Consumption(bun, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventoryLevel(bun, true, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventoryLevel(bun, true, -1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> group("Bad", 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> group("Bad", -1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
