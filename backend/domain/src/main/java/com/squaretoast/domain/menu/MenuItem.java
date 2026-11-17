package com.squaretoast.domain.menu;

import com.squaretoast.domain.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A sellable item, what one unit consumes, and the (possibly shared) modifier groups it offers. */
public record MenuItem(UUID id, String name, Money price, boolean active, List<Consumption> consumes,
                       List<ModifierGroup> modifierGroups) {

    public MenuItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(price, "price");
        consumes = List.copyOf(consumes);
        modifierGroups = List.copyOf(modifierGroups);
    }
}
