package com.squaretoast.domain.menu;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import java.util.List;
import java.util.UUID;

/** Small builders for menu unit tests. */
final class MenuFixtures {

    static final CurrencyCode USD = CurrencyCode.of("USD");

    private MenuFixtures() {}

    static UUID id() {
        return UUID.randomUUID();
    }

    static InventoryLevel tracked(UUID id, int onHand) {
        return new InventoryLevel(id, true, onHand, false);
    }

    static InventoryLevel untracked(UUID id) {
        return new InventoryLevel(id, false, null, false);
    }

    static InventoryLevel eightySixed(UUID id) {
        return new InventoryLevel(id, false, null, true);
    }

    static ModifierOption option(String name, long deltaCents, Consumption... consumes) {
        return new ModifierOption(id(), name, deltaCents, true, List.of(consumes));
    }

    static ModifierGroup group(String name, int min, int max, ModifierOption... options) {
        return new ModifierGroup(id(), name, min, max, List.of(options));
    }

    static MenuItem item(String name, long priceCents, List<ModifierGroup> groups, Consumption... consumes) {
        return new MenuItem(id(), name, Money.ofCents(priceCents, USD), true, List.of(consumes), groups);
    }
}
