package com.squaretoast.domain.menu;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A modifier group. The same group linked to several items is a "linked modifier". */
public record ModifierGroup(UUID id, String name, int minSelect, int maxSelect, List<ModifierOption> options) {

    public ModifierGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (minSelect < 0 || maxSelect < minSelect) {
            throw new IllegalArgumentException("need 0 <= minSelect <= maxSelect, got " + minSelect + ".." + maxSelect);
        }
        options = List.copyOf(options);
    }
}
