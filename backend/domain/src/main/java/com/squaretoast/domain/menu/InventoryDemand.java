package com.squaretoast.domain.menu;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Total inventory an order consumes. Keys iterate in ascending {@link UUID} order, and every commit locks
 * inventory rows in that one order, so concurrent commits cannot deadlock on each other.
 */
public final class InventoryDemand {

    private InventoryDemand() {}

    public static SortedMap<UUID, Long> of(List<LineDemand> lines) {
        TreeMap<UUID, Long> demand = new TreeMap<>();
        for (LineDemand line : lines) {
            add(demand, line.item().consumes(), line.qty());
            for (UUID optionId : line.optionIds()) {
                add(demand, optionOf(line.item(), optionId).consumes(), line.qty());
            }
        }
        return Collections.unmodifiableSortedMap(demand);
    }

    private static void add(Map<UUID, Long> demand, List<Consumption> consumes, int lineQty) {
        for (Consumption c : consumes) {
            long units = Math.multiplyExact((long) c.qty(), (long) lineQty);
            demand.merge(c.inventoryItemId(), units, Math::addExact);
        }
    }

    private static ModifierOption optionOf(MenuItem item, UUID optionId) {
        for (ModifierGroup g : item.modifierGroups()) {
            for (ModifierOption o : g.options()) {
                if (o.id().equals(optionId)) {
                    return o;
                }
            }
        }
        throw new IllegalArgumentException("option " + optionId + " is not offered on " + item.id());
    }
}
