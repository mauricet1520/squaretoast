package com.squaretoast.domain.labor;

import com.squaretoast.domain.money.CurrencyMismatchException;
import com.squaretoast.domain.money.Money;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Card fee an employer may recover from a card tip (CLAUDE.md 5). Hard-blocked in CA, ME, MA and DE whatever the
 * state profile says; elsewhere only if the profile allows it, and only floor(fee x tip / charged total): the
 * tip's exact proportional share, never on the food subtotal, never rounded up.
 */
public final class TipFeeCalculator {

    /** Hard-coded guard, independent of seed data. */
    public static final Set<String> BLOCKED_STATES = Set.of("CA", "ME", "MA", "DE");
    private static final Pattern STATE = Pattern.compile("[A-Z]{2}");

    private TipFeeCalculator() {}

    /**
     * Returns the part of {@code totalCardFee} attributable to the tip.
     *
     * @param totalCardFee the merchant's actual cost for the charge (processor fee plus application fee)
     */
    public static Money deductibleFee(String stateCode, boolean stateAllowsDeduction, Money tip, Money chargedTotal,
                                      Money totalCardFee) {
        if (!STATE.matcher(stateCode).matches()) {
            throw new IllegalArgumentException("state code must be two uppercase letters: " + stateCode);
        }
        if (BLOCKED_STATES.contains(stateCode) || !stateAllowsDeduction) {
            throw new ProhibitedDeductionException(stateCode);
        }
        requireSameCurrency(tip, chargedTotal);
        requireSameCurrency(tip, totalCardFee);
        if (tip.isNegative() || totalCardFee.isNegative()) {
            throw new IllegalArgumentException("tip and fee cannot be negative");
        }
        if (chargedTotal.cents() <= 0 || tip.cents() > chargedTotal.cents()) {
            throw new IllegalArgumentException("need 0 <= tip <= charged total and a positive charged total");
        }
        return Money.ofCents(Math.multiplyExact(totalCardFee.cents(), tip.cents()) / chargedTotal.cents(), tip.currency());
    }

    private static void requireSameCurrency(Money a, Money b) {
        if (!a.currency().equals(b.currency())) {
            throw new CurrencyMismatchException(a.currency(), b.currency());
        }
    }
}
