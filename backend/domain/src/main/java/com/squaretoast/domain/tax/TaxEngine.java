package com.squaretoast.domain.tax;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.CurrencyMismatchException;
import com.squaretoast.domain.money.Money;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sales tax for one check (plan §4).
 * <ul>
 *   <li>Rate for a category = Σ over jurisdictions of that jurisdiction's rule; a channel-specific rule replaces the
 *       jurisdiction's any-channel rule. No effective rule for a taxable category fails closed.</li>
 *   <li>CA cold food to go (takeout or delivery) is exempt unless the location meets 80/80 and has not elected
 *       separate accounting. Hot food and dine-in are taxable.</li>
 *   <li>Voluntary tips are never taxable. Mandatory charges and auto-gratuities follow the state profile.</li>
 *   <li>Tax is rounded half-up once for the whole check, never per line.</li>
 * </ul>
 */
public final class TaxEngine {

    private static final String CALIFORNIA = "CA";

    private TaxEngine() {}

    public static TaxResult compute(TaxContext ctx, List<TaxRule> rules, List<TaxableLine> lines,
                                    List<ServiceCharge> charges, Money voluntaryTip) {
        CurrencyCode currency = ctx.currency();
        requireCurrency(voluntaryTip, currency);
        if (voluntaryTip.isNegative()) {
            throw new IllegalArgumentException("tip cannot be negative: " + voluntaryTip);
        }

        long taxable = 0;
        long exempt = 0;
        long taxMicroCents = 0; // cents x ppm
        for (TaxableLine line : lines) {
            requireCurrency(line.amount(), currency);
            long cents = line.amount().cents();
            if (isCaliforniaColdFoodToGoExempt(ctx, line)) {
                exempt = Math.addExact(exempt, cents);
            } else {
                taxable = Math.addExact(taxable, cents);
                taxMicroCents = Math.addExact(taxMicroCents, Math.multiplyExact(cents, (long) rate(ctx, rules, line.taxCategory())));
            }
        }
        for (ServiceCharge charge : charges) {
            requireCurrency(charge.amount(), currency);
            long cents = charge.amount().cents();
            boolean isTaxable = switch (charge.kind()) {
                case MANDATORY, AUTO_GRATUITY -> ctx.mandatoryServiceChargeTaxable();
                case DELIVERY_FEE -> charge.deliveryFeeTaxable();
            };
            if (isTaxable) {
                taxable = Math.addExact(taxable, cents);
                taxMicroCents = Math.addExact(taxMicroCents,
                        Math.multiplyExact(cents, (long) rate(ctx, rules, TaxCategory.SERVICE_CHARGE)));
            } else {
                exempt = Math.addExact(exempt, cents);
            }
        }
        long tax = Math.addExact(taxMicroCents, TaxRule.PPM / 2) / TaxRule.PPM; // half-up; amounts are non-negative
        return new TaxResult(Money.ofCents(taxable, currency), Money.ofCents(exempt, currency), Money.ofCents(tax, currency));
    }

    private static boolean isCaliforniaColdFoodToGoExempt(TaxContext ctx, TaxableLine line) {
        return CALIFORNIA.equals(ctx.stateCode())
                && line.temp() == FoodTemp.COLD
                && TaxCategory.FOOD.equals(line.taxCategory())
                && ctx.channel() != FulfillmentChannel.DINE_IN
                && !(ctx.ca8080Applies() && !ctx.caSeparateAccountingElected());
    }

    /** Combined ppm for a category: per jurisdiction, the channel-specific rule wins over the any-channel rule. */
    private static int rate(TaxContext ctx, List<TaxRule> rules, String category) {
        Map<String, TaxRule> specific = new HashMap<>();
        Map<String, TaxRule> anyChannel = new HashMap<>();
        for (TaxRule r : rules) {
            FulfillmentChannel ch = r.channel();
            if (!r.stateCode().equals(ctx.stateCode()) || !r.taxCategory().equals(category) || !r.effectiveOn(ctx.date())
                    || (ch != null && ch != ctx.channel())) {
                continue;
            }
            Map<String, TaxRule> slot = ch == null ? anyChannel : specific;
            if (slot.putIfAbsent(r.jurisdictionCode(), r) != null) {
                throw new IllegalStateException("two effective " + category + " rules for jurisdiction " + r.jurisdictionCode());
            }
        }
        if (specific.isEmpty() && anyChannel.isEmpty()) {
            throw new MissingTaxRuleException(ctx.stateCode(), category, ctx.channel());
        }
        Map<String, TaxRule> combined = new HashMap<>(anyChannel);
        combined.putAll(specific);
        int ppm = 0;
        for (TaxRule r : combined.values()) {
            ppm = Math.addExact(ppm, r.ratePpm());
        }
        return ppm;
    }

    private static void requireCurrency(Money m, CurrencyCode expected) {
        if (!m.currency().equals(expected)) {
            throw new CurrencyMismatchException(expected, m.currency());
        }
    }
}
