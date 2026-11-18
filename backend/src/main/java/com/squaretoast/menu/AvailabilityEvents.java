package com.squaretoast.menu;

import com.squaretoast.domain.menu.Availability;
import com.squaretoast.realtime.Topics;
import com.squaretoast.domain.menu.Consumption;
import com.squaretoast.domain.menu.InventoryLevel;
import com.squaretoast.domain.menu.MenuItem;
import com.squaretoast.domain.menu.ModifierGroup;
import com.squaretoast.domain.menu.ModifierOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Builds the "availability" SSE payload: changed inventory plus every item/option whose 86 state depends on it. */
public final class AvailabilityEvents {

    public static final String TOPIC = Topics.AVAILABILITY;

    private AvailabilityEvents() {}

    public static Payload payload(LocationMenu menu, Set<UUID> changedInventory) {
        Availability a = Availability.of(menu.levels());

        List<InventoryState> inventory = new ArrayList<>();
        for (UUID id : new TreeSet<>(changedInventory)) {
            InventoryLevel l = menu.levels().get(id);
            if (l != null) {
                inventory.add(new InventoryState(id, l.onHand(), l.manually86ed()));
            }
        }

        Map<UUID, ModifierOption> affectedOptions = new LinkedHashMap<>();
        for (MenuItem item : menu.items()) {
            for (ModifierGroup g : item.modifierGroups()) {
                for (ModifierOption o : g.options()) {
                    if (touches(o.consumes(), changedInventory)) {
                        affectedOptions.putIfAbsent(o.id(), o);
                    }
                }
            }
        }

        List<Available> items = new ArrayList<>();
        for (MenuItem item : menu.items()) {
            boolean viaOption = item.modifierGroups().stream()
                    .flatMap(g -> g.options().stream())
                    .anyMatch(o -> affectedOptions.containsKey(o.id()));
            if (viaOption || touches(item.consumes(), changedInventory)) {
                items.add(new Available(item.id(), a.item(item)));
            }
        }
        List<Available> options = affectedOptions.values().stream().map(o -> new Available(o.id(), a.option(o))).toList();
        return new Payload(inventory, items, options);
    }

    private static boolean touches(List<Consumption> consumes, Set<UUID> changed) {
        return consumes.stream().anyMatch(c -> changed.contains(c.inventoryItemId()));
    }

    /** Event body. */
    public record Payload(List<InventoryState> inventory, List<Available> menuItems, List<Available> modifierOptions) {}

    /** {@code onHand} is null for untracked inventory. */
    public record InventoryState(UUID id, @Nullable Integer onHand, boolean eightySixed) {}

    public record Available(UUID id, boolean available) {}
}
