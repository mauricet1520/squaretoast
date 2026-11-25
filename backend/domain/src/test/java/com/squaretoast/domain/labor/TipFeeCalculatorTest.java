package com.squaretoast.domain.labor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.CurrencyMismatchException;
import com.squaretoast.domain.money.Money;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** CLAUDE.md 5: hard-blocked in CA/ME/MA/DE; elsewhere only the exact proportional fee on the tip, never rounded up. */
class TipFeeCalculatorTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static Money usd(long cents) {
        return Money.ofCents(cents, USD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CA", "ME", "MA", "DE"})
    void blockedStatesThrowEvenIfTheirProfileClaimsDeductionIsAllowed(String state) {
        assertThatThrownBy(() -> TipFeeCalculator.deductibleFee(state, true, usd(2000), usd(12_000), usd(378)))
                .isInstanceOf(ProhibitedDeductionException.class);
    }

    @Test
    void stateProfileThatDisallowsDeductionThrows() {
        assertThatThrownBy(() -> TipFeeCalculator.deductibleFee("NY", false, usd(2000), usd(12_000), usd(378)))
                .isInstanceOf(ProhibitedDeductionException.class);
    }

    @Test
    void deductsOnlyTheTipsProportionalShareOfTheFeeFlooredToTheCent() {
        assertThat(TipFeeCalculator.deductibleFee("US", true, usd(2000), usd(12_000), usd(378))).isEqualTo(usd(63));
        assertThat(TipFeeCalculator.deductibleFee("US", true, usd(2000), usd(12_000), usd(383))).isEqualTo(usd(63)); // 63.83
        assertThat(TipFeeCalculator.deductibleFee("US", true, usd(1), usd(12_000), usd(383))).isEqualTo(usd(0));    // 0.03
        assertThat(TipFeeCalculator.deductibleFee("US", true, usd(0), usd(12_000), usd(383))).isEqualTo(usd(0));
    }

    @Test
    void inputsAreValidated() {
        assertThatThrownBy(() -> TipFeeCalculator.deductibleFee("US", true, usd(13_000), usd(12_000), usd(383)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TipFeeCalculator.deductibleFee("US", true, usd(0), usd(0), usd(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TipFeeCalculator.deductibleFee("US", true, usd(-1), usd(100), usd(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TipFeeCalculator.deductibleFee("US", true, usd(10), usd(100), usd(-3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TipFeeCalculator.deductibleFee("ca", true, usd(10), usd(100), usd(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TipFeeCalculator.deductibleFee("US", true, usd(10), usd(100),
                Money.ofCents(3, CurrencyCode.of("EUR")))).isInstanceOf(CurrencyMismatchException.class);
    }
}
