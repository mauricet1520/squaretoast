package com.squaretoast.domain.labor;

/**
 * Tipped overtime (CLAUDE.md 5): the overtime cash rate is 1.5 x the FULL minimum wage less the tip credit, never
 * 1.5 x the cash wage. Fractions of a cent round up, in the employee's favour. Federal 40-hour weekly threshold
 * only; state daily overtime and payroll are out of scope (plan §4).
 */
public final class OvertimeCalculator {

    private static final int WEEKLY_THRESHOLD_HOURS_X100 = 40 * 100;

    private OvertimeCalculator() {}

    /** 1.5 x min - (min - cash) = (min + 2 x cash) / 2, rounded up to the cent. */
    public static long overtimeCashRateCents(TippedWage w) {
        return Math.ceilDiv(otRateHalfCents(w), 2);
    }

    /** Cash wages for a week of {@code hoursX100} (e.g. 4025 = 40.25 h), rounded up once to the cent. */
    public static long weeklyCashWagesCents(TippedWage w, int hoursX100) {
        if (hoursX100 < 0) {
            throw new IllegalArgumentException("hours cannot be negative: " + hoursX100);
        }
        long regular = Math.min(hoursX100, WEEKLY_THRESHOLD_HOURS_X100);
        long overtime = Math.max(hoursX100 - WEEKLY_THRESHOLD_HOURS_X100, 0);
        // Work in 1/200 cent: regular = cash x h/100, overtime = (min + 2 cash)/2 x h/100.
        long units = Math.addExact(
                Math.multiplyExact(Math.multiplyExact(2, w.cashWageCents()), regular),
                Math.multiplyExact(otRateHalfCents(w), overtime));
        return Math.ceilDiv(units, 200);
    }

    private static long otRateHalfCents(TippedWage w) {
        return Math.addExact(w.minWageCents(), Math.multiplyExact(2, w.cashWageCents()));
    }
}
