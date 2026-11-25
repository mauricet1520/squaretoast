package com.squaretoast.domain.labor;

import java.util.Objects;
import java.util.UUID;

/** The staff facts that tip-pool eligibility depends on; mirrors one row of the employee table. */
public record TipPoolCandidate(UUID employeeId, boolean manager, boolean supervisor, int ownerEquityBps,
                               boolean ownerActiveMgmt, boolean boh) {

    public TipPoolCandidate {
        Objects.requireNonNull(employeeId, "employeeId");
        if (ownerEquityBps < 0 || ownerEquityBps > 10_000) {
            throw new IllegalArgumentException("owner equity must be 0..10000 bps: " + ownerEquityBps);
        }
    }
}
