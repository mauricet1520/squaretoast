package com.squaretoast.domain.menu;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One order line as inventory sees it: the item, how many, and the chosen option ids. */
public record LineDemand(MenuItem item, int qty, List<UUID> optionIds) {

    public LineDemand {
        Objects.requireNonNull(item, "item");
        if (qty <= 0) {
            throw new IllegalArgumentException("line qty must be positive: " + qty);
        }
        optionIds = List.copyOf(optionIds);
    }
}
