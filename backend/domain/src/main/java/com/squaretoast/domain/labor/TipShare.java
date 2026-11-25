package com.squaretoast.domain.labor;

import java.util.Objects;
import java.util.UUID;

/** A pool member's weight: hours x100 or points, depending on the pool's distribution basis. */
public record TipShare(UUID employeeId, long weight) {

    public TipShare {
        Objects.requireNonNull(employeeId, "employeeId");
        if (weight < 0) {
            throw new IllegalArgumentException("weight cannot be negative: " + weight);
        }
    }
}
