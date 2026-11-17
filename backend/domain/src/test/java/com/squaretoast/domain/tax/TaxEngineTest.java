package com.squaretoast.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.CurrencyMismatchException;
import com.squaretoast.domain.money.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Plan §4: rate = Σ jurisdiction rules for (state, category, channel|any), rounded half-up once per check. */
class TaxEngineTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    /** San Francisco-style combined rate, 8.625%. */
    private static final List<TaxRule> CA_RULES = List.of(
            new TaxRule("CA", "CA-SF", "FOOD", null, 86_250, START, null),
            new TaxRule("CA", "CA-SF", "SOFT_DRINK", null, 86_250, START, null),
            new TaxRule("CA", "CA-SF", TaxCategory.SERVICE_CHARGE, null, 86_250, START, null));

    private static Money usd(long cents) {
        return Money.ofCents(cents, USD);
    }

    private static TaxContext ca(FulfillmentChannel channel, boolean eightyEighty, boolean separateAccounting) {
        return new TaxContext("CA", TODAY, channel, eightyEighty, separateAccounting, true, USD);
    }

    private static TaxResult tax(TaxContext ctx, List<TaxRule> rules, List<TaxableLine> lines) {
        return TaxEngine.compute(ctx, rules, lines, List.of(), usd(0));
    }

    @ParameterizedTest(name = "{0} {1} {2} 80/80={3} separate={4} -> {5}")
    @CsvSource({
            // category, temp, channel, 80/80 applies, separate accounting elected, tax on $10.00
            "FOOD,       HOT,  DINE_IN,  false, false, 86",
            "FOOD,       HOT,  TAKEOUT,  false, false, 86",
            "FOOD,       HOT,  DELIVERY, false, false, 86",
            "FOOD,       COLD, DINE_IN,  false, false, 86",
            "FOOD,       COLD, TAKEOUT,  false, false, 0",
            "FOOD,       COLD, DELIVERY, false, false, 0",
            "FOOD,       COLD, TAKEOUT,  true,  false, 86",
            "FOOD,       COLD, DELIVERY, true,  false, 86",
            "FOOD,       COLD, TAKEOUT,  true,  true,  0",
            "FOOD,       HOT,  TAKEOUT,  true,  true,  86",
            "FOOD,       COLD, DINE_IN,  true,  true,  86",
            "FOOD,       NA,   TAKEOUT,  false, false, 86",
            "SOFT_DRINK, COLD, TAKEOUT,  false, false, 86",
    })
    void californiaColdFoodToGoMatrix(String category, FoodTemp temp, FulfillmentChannel channel,
                                      boolean eightyEighty, boolean separate, long expectedCents) {
        TaxResult r = tax(ca(channel, eightyEighty, separate), CA_RULES, List.of(new TaxableLine(category, temp, usd(1000))));
        assertThat(r.tax()).isEqualTo(usd(expectedCents));
        assertThat(r.taxableBase().plus(r.exemptBase())).isEqualTo(usd(1000));
        assertThat(r.exemptBase()).isEqualTo(usd(expectedCents == 0 ? 1000 : 0));
    }

    @Test
    void coldToGoExemptionIsCaliforniaOnly() {
        List<TaxRule> rules = List.of(new TaxRule("US", "US-X", "FOOD", null, 50_000, START, null));
        TaxContext other = new TaxContext("US", TODAY, FulfillmentChannel.TAKEOUT, false, false, true, USD);
        assertThat(tax(other, rules, List.of(new TaxableLine("FOOD", FoodTemp.COLD, usd(1000)))).tax()).isEqualTo(usd(50));
    }

    @Test
    void roundsHalfUpOncePerCheckNotPerLine() {
        List<TaxRule> fivePercent = List.of(new TaxRule("CA", "CA-X", "FOOD", null, 50_000, START, null));
        List<TaxableLine> lines = List.of(
                new TaxableLine("FOOD", FoodTemp.HOT, usd(10)),
                new TaxableLine("FOOD", FoodTemp.HOT, usd(10)),
                new TaxableLine("FOOD", FoodTemp.HOT, usd(10)));
        // 3 x 0.5c: per-line rounding would give 3c; the check owes 1.5c -> 2c.
        assertThat(tax(ca(FulfillmentChannel.DINE_IN, false, false), fivePercent, lines).tax()).isEqualTo(usd(2));
        assertThat(tax(ca(FulfillmentChannel.DINE_IN, false, false), fivePercent,
                List.of(new TaxableLine("FOOD", FoodTemp.HOT, usd(29)))).tax()).isEqualTo(usd(1));
    }

    @Test
    void sumsJurisdictionsAndChannelSpecificRuleOverridesAnyChannelForThatJurisdiction() {
        List<TaxRule> rules = List.of(
                new TaxRule("CA", "STATE", "FOOD", null, 60_000, START, null),
                new TaxRule("CA", "CITY", "FOOD", null, 25_000, START, null),
                new TaxRule("CA", "CITY", "FOOD", FulfillmentChannel.TAKEOUT, 10_000, START, null));
        List<TaxableLine> hot = List.of(new TaxableLine("FOOD", FoodTemp.HOT, usd(10_000)));

        assertThat(tax(ca(FulfillmentChannel.DINE_IN, false, false), rules, hot).tax()).isEqualTo(usd(850));
        assertThat(tax(ca(FulfillmentChannel.TAKEOUT, false, false), rules, hot).tax()).isEqualTo(usd(700));
    }

    @Test
    void onlyRulesEffectiveOnTheDateApply() {
        List<TaxRule> rules = List.of(
                new TaxRule("CA", "CA-X", "FOOD", null, 70_000, START, TODAY),               // ended today (exclusive)
                new TaxRule("CA", "CA-X", "FOOD", null, 80_000, TODAY, null),                // starts today
                new TaxRule("CA", "CA-Y", "FOOD", null, 10_000, TODAY.plusDays(1), null));   // future
        assertThat(tax(ca(FulfillmentChannel.DINE_IN, false, false), rules,
                List.of(new TaxableLine("FOOD", FoodTemp.HOT, usd(1000)))).tax()).isEqualTo(usd(80));
    }

    @Test
    void rulesForOtherStatesAreIgnored() {
        List<TaxRule> rules = List.of(
                new TaxRule("CA", "CA-X", "FOOD", null, 80_000, START, null),
                new TaxRule("MA", "MA-X", "FOOD", null, 62_500, START, null));
        assertThat(tax(ca(FulfillmentChannel.DINE_IN, false, false), rules,
                List.of(new TaxableLine("FOOD", FoodTemp.HOT, usd(1000)))).tax()).isEqualTo(usd(80));
    }

    @Test
    void categoryWithNoRuleFailsClosedInsteadOfGoingUntaxed() {
        assertThatThrownBy(() -> tax(ca(FulfillmentChannel.DINE_IN, false, false), CA_RULES,
                List.of(new TaxableLine("ALCOHOL", FoodTemp.NA, usd(800)))))
                .isInstanceOf(MissingTaxRuleException.class);
    }

    @Test
    void ambiguousDuplicateRulesAreRejected() {
        List<TaxRule> rules = List.of(
                new TaxRule("CA", "CA-X", "FOOD", null, 80_000, START, null),
                new TaxRule("CA", "CA-X", "FOOD", null, 90_000, START, null));
        assertThatThrownBy(() -> tax(ca(FulfillmentChannel.DINE_IN, false, false), rules,
                List.of(new TaxableLine("FOOD", FoodTemp.HOT, usd(1000)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void voluntaryTipIsNeverTaxed() {
        List<TaxableLine> lines = List.of(new TaxableLine("FOOD", FoodTemp.HOT, usd(1000)));
        TaxContext ctx = ca(FulfillmentChannel.DINE_IN, false, false);
        TaxResult noTip = TaxEngine.compute(ctx, CA_RULES, lines, List.of(), usd(0));
        TaxResult bigTip = TaxEngine.compute(ctx, CA_RULES, lines, List.of(), usd(5000));
        assertThat(bigTip).isEqualTo(noTip);
        assertThat(bigTip.taxableBase()).isEqualTo(usd(1000));
    }

    @Test
    void mandatoryChargesAreModelledSeparatelyAndTaxedPerStateProfile() {
        List<TaxableLine> lines = List.of(new TaxableLine("FOOD", FoodTemp.HOT, usd(1000)));
        List<ServiceCharge> charges = List.of(ServiceCharge.mandatory(usd(200)), ServiceCharge.autoGratuity(usd(180)));
        TaxContext taxable = new TaxContext("CA", TODAY, FulfillmentChannel.DINE_IN, false, false, true, USD);
        TaxContext untaxed = new TaxContext("CA", TODAY, FulfillmentChannel.DINE_IN, false, false, false, USD);

        TaxResult withTax = TaxEngine.compute(taxable, CA_RULES, lines, charges, usd(0));
        TaxResult withoutTax = TaxEngine.compute(untaxed, CA_RULES, lines, charges, usd(0));

        assertThat(withTax.taxableBase()).isEqualTo(usd(1380));
        assertThat(withTax.tax()).isEqualTo(usd(119)); // 1380 x 8.625% = 119.025
        assertThat(withoutTax.taxableBase()).isEqualTo(usd(1000));
        assertThat(withoutTax.exemptBase()).isEqualTo(usd(380));
        assertThat(withoutTax.tax()).isEqualTo(usd(86));
    }

    @Test
    void deliveryFeeTaxabilityIsItsOwnSetting() {
        List<TaxableLine> lines = List.of(new TaxableLine("FOOD", FoodTemp.HOT, usd(1000)));
        TaxContext ctx = ca(FulfillmentChannel.DELIVERY, false, false);
        assertThat(TaxEngine.compute(ctx, CA_RULES, lines, List.of(ServiceCharge.deliveryFee(usd(500), true)), usd(0)).tax())
                .isEqualTo(usd(129)); // 1500 x 8.625% = 129.375
        assertThat(TaxEngine.compute(ctx, CA_RULES, lines, List.of(ServiceCharge.deliveryFee(usd(500), false)), usd(0)).tax())
                .isEqualTo(usd(86));
    }

    @Test
    void taxableServiceChargeWithNoRuleFailsClosed() {
        List<TaxRule> foodOnly = List.of(new TaxRule("CA", "CA-X", "FOOD", null, 80_000, START, null));
        assertThatThrownBy(() -> TaxEngine.compute(ca(FulfillmentChannel.DINE_IN, false, false), foodOnly,
                List.of(), List.of(ServiceCharge.mandatory(usd(200))), usd(0)))
                .isInstanceOf(MissingTaxRuleException.class);
    }

    @Test
    void inputsAreValidated() {
        assertThatThrownBy(() -> new TaxableLine("FOOD", FoodTemp.HOT, usd(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceCharge.mandatory(usd(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaxRule("CA", "X", "FOOD", null, 1_000_001, START, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaxRule("CA", "X", "FOOD", null, 1, START, START)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaxEngine.compute(ca(FulfillmentChannel.DINE_IN, false, false), CA_RULES,
                List.of(new TaxableLine("FOOD", FoodTemp.HOT, Money.ofCents(100, CurrencyCode.of("EUR")))), List.of(), usd(0)))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> TaxEngine.compute(ca(FulfillmentChannel.DINE_IN, false, false), CA_RULES,
                List.of(), List.of(), usd(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
