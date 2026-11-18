package com.squaretoast.domain.payment;

import com.squaretoast.domain.money.Money;

/**
 * The platform's only revenue (CLAUDE.md mission): {@code floor(amount x spreadBps / 10 000) + fixedCents}, taken on
 * the amount incl. tip. The floor always favours the merchant, and the fee never exceeds the charge.
 */
public final class ApplicationFeeCalculator {

    public static final int BPS = 10_000;

    private ApplicationFeeCalculator() {}

    public static Money fee(Money amount, int spreadBps, long fixedCents) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("amount cannot be negative: " + amount);
        }
        if (spreadBps < 0 || spreadBps > BPS) {
            throw new IllegalArgumentException("spread must be 0..10000 bps: " + spreadBps);
        }
        if (fixedCents < 0) {
            throw new IllegalArgumentException("fixed fee cannot be negative: " + fixedCents);
        }
        long fee = Math.addExact(Math.multiplyExact(amount.cents(), spreadBps) / BPS, fixedCents);
        return Money.ofCents(Math.min(fee, amount.cents()), amount.currency());
    }
}
