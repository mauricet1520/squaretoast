package com.squaretoast.domain.menu;

import com.squaretoast.domain.menu.InvalidModifierSelectionException.Reason;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Validates a line's modifier choices against the groups linked to that item (min/max per group). */
public final class ModifierSelection {

    private ModifierSelection() {}

    public static void validate(MenuItem item, List<UUID> selectedOptionIds) {
        Map<UUID, ModifierGroup> groupOf = new HashMap<>();
        Map<UUID, ModifierOption> optionById = new HashMap<>();
        for (ModifierGroup g : item.modifierGroups()) {
            for (ModifierOption o : g.options()) {
                groupOf.put(o.id(), g);
                optionById.put(o.id(), o);
            }
        }
        Set<UUID> seen = new HashSet<>();
        Map<UUID, Integer> countByGroup = new HashMap<>();
        for (UUID id : selectedOptionIds) {
            ModifierOption option = optionById.get(id);
            ModifierGroup group = groupOf.get(id);
            if (option == null || group == null) {
                throw new InvalidModifierSelectionException(Reason.UNKNOWN_OPTION, "option " + id + " is not offered on " + item.id());
            }
            if (!seen.add(id)) {
                throw new InvalidModifierSelectionException(Reason.DUPLICATE_OPTION, "option " + id + " chosen twice");
            }
            if (!option.active()) {
                throw new InvalidModifierSelectionException(Reason.INACTIVE_OPTION, "option " + id + " is inactive");
            }
            countByGroup.merge(group.id(), 1, Integer::sum);
        }
        for (ModifierGroup g : item.modifierGroups()) {
            int n = countByGroup.getOrDefault(g.id(), 0);
            if (n < g.minSelect()) {
                throw new InvalidModifierSelectionException(Reason.TOO_FEW, g.name() + " needs at least " + g.minSelect());
            }
            if (n > g.maxSelect()) {
                throw new InvalidModifierSelectionException(Reason.TOO_MANY, g.name() + " allows at most " + g.maxSelect());
            }
        }
    }
}
