package com.squaretoast.domain.menu;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Stock state of one inventory item. Untracked items have no count and run out only when 86'd by hand. */
public record InventoryLevel(UUID id, boolean tracked, @Nullable Integer onHand, boolean manually86ed) {

    public InventoryLevel {
        Objects.requireNonNull(id, "id");
        if (tracked && onHand == null) {
            throw new IllegalArgumentException("tracked inventory needs an on-hand count: " + id);
        }
        if (onHand != null && onHand < 0) {
            throw new IllegalArgumentException("on-hand count cannot be negative: " + id);
        }
    }

    /** True when {@code qty} units can be consumed right now. */
    public boolean canSupply(long qty) {
        if (manually86ed) {
            return false;
        }
        Integer count = onHand;
        return !tracked || (count != null && count >= qty);
    }
}
