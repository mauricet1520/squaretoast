package com.squaretoast.menu;

import com.squaretoast.domain.menu.InventoryLevel;
import com.squaretoast.domain.menu.MenuItem;
import com.squaretoast.domain.money.CurrencyCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One location's menu with the inventory levels its availability is derived from. */
public record LocationMenu(UUID locationId, CurrencyCode currency, List<MenuItem> items, Map<UUID, InventoryLevel> levels) {

    public LocationMenu {
        items = List.copyOf(items);
        levels = Map.copyOf(levels);
    }

    public Optional<MenuItem> item(UUID id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst();
    }

    public LocationMenu withLevels(Map<UUID, InventoryLevel> newLevels) {
        return new LocationMenu(locationId, currency, items, newLevels);
    }
}
