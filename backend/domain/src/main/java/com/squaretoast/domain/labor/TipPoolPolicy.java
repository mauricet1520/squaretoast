package com.squaretoast.domain.labor;

import com.squaretoast.domain.labor.TipPoolIneligibleException.Reason;

/**
 * FLSA tip-pool eligibility (CLAUDE.md 5). Managers, supervisors and owners holding 20%+ who actively manage are
 * never pool members; BOH staff are barred when the location takes a tip credit. The database enforces the same
 * rules with triggers (V1-V3), so neither layer can be bypassed alone.
 */
public final class TipPoolPolicy {

    /** 20% equity, in basis points. */
    public static final int OWNER_THRESHOLD_BPS = 2000;

    private TipPoolPolicy() {}

    public static void check(TipPoolCandidate c, boolean locationUsesTipCredit) {
        if (c.manager()) {
            throw new TipPoolIneligibleException(c.employeeId(), Reason.MANAGER);
        }
        if (c.supervisor()) {
            throw new TipPoolIneligibleException(c.employeeId(), Reason.SUPERVISOR);
        }
        if (c.ownerEquityBps() >= OWNER_THRESHOLD_BPS && c.ownerActiveMgmt()) {
            throw new TipPoolIneligibleException(c.employeeId(), Reason.ACTIVE_OWNER);
        }
        if (c.boh() && locationUsesTipCredit) {
            throw new TipPoolIneligibleException(c.employeeId(), Reason.BOH_AT_TIP_CREDIT_LOCATION);
        }
    }
}
