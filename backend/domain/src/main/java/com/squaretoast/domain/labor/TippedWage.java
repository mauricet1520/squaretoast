package com.squaretoast.domain.labor;

/**
 * Hourly minimum wage and the cash wage actually paid, in cents. The tip credit is the difference; without a tip
 * credit the two are equal.
 */
public record TippedWage(long minWageCents, long cashWageCents) {

    public TippedWage {
        if (cashWageCents <= 0 || cashWageCents > minWageCents) {
            throw new IllegalArgumentException("need 0 < cash wage <= minimum wage, got " + cashWageCents + " / " + minWageCents);
        }
    }

    public long tipCreditCents() {
        return minWageCents - cashWageCents;
    }
}
