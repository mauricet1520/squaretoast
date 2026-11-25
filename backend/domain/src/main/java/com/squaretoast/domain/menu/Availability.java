package com.squaretoast.domain.menu;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Availability derived from inventory, never stored on the menu row. An item or option is 86'd when it is
 * inactive, or any inventory it consumes is manually 86'd or short. An item is also 86'd when a required
 * modifier group has fewer available options than its minimum.
 */
public final class Availability {

    private final Map<UUID, InventoryLevel> levels;

    private Availability(Map<UUID, InventoryLevel> levels) {
        this.levels = Map.copyOf(levels);
    }

    public static Availability of(Map<UUID, InventoryLevel> levels) {
        return new Availability(levels);
    }

    public boolean option(ModifierOption option) {
        return option.active() && supplied(option.consumes());
    }

    public boolean item(MenuItem item) {
        if (!item.active() || !supplied(item.consumes())) {
            return false;
        }
        for (ModifierGroup group : item.modifierGroups()) {
            if (group.minSelect() > 0 && group.options().stream().filter(this::option).count() < group.minSelect()) {
                return false;
            }
        }
        return true;
    }

    private boolean supplied(List<Consumption> consumes) {
        for (Consumption c : consumes) {
            InventoryLevel level = levels.get(c.inventoryItemId());
            if (level == null) {
                throw new IllegalArgumentException("no inventory level for " + c.inventoryItemId());
            }
            if (!level.canSupply(c.qty())) {
                return false;
            }
        }
        return true;
    }
}
