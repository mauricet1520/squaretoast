package com.squaretoast.domain.menu;

import java.util.Objects;
import java.util.UUID;

/** One unit of a menu item or modifier option consumes {@code qty} of an inventory item. */
public record Consumption(UUID inventoryItemId, int qty) {

    public Consumption {
        Objects.requireNonNull(inventoryItemId, "inventoryItemId");
        if (qty <= 0) {
            throw new IllegalArgumentException("consumption qty must be positive: " + qty);
        }
    }
}
