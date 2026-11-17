package com.squaretoast.domain.labor;

import java.util.UUID;

/** The employee may not share in a tip pool under FLSA. */
public final class TipPoolIneligibleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the employee was rejected. */
    public enum Reason { MANAGER, SUPERVISOR, ACTIVE_OWNER, BOH_AT_TIP_CREDIT_LOCATION }

    private final Reason reason;

    public TipPoolIneligibleException(UUID employeeId, Reason reason) {
        super("employee " + employeeId + " cannot join a tip pool: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
