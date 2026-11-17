package com.squaretoast.domain.menu;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A choice inside a modifier group. {@code priceDeltaCents} may be negative ("no cheese"). */
public record ModifierOption(UUID id, String name, long priceDeltaCents, boolean active, List<Consumption> consumes) {

    public ModifierOption {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        consumes = List.copyOf(consumes);
    }
}
