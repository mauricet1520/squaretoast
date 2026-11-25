package com.squaretoast.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import org.junit.jupiter.api.Test;

/** application_fee = floor(amount x spread_bps / 10 000) + fixed_cents, on the amount incl. tip (plan §4). */
class ApplicationFeeCalculatorTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static Money usd(long cents) {
        return Money.ofCents(cents, USD);
    }

    @Test
    void spreadIsFlooredInTheMerchantsFavour() {
        // $12.34 x 0.15% = 1.851c -> 1c
        assertThat(ApplicationFeeCalculator.fee(usd(1234), 15, 0)).isEqualTo(usd(1));
        // $99.99 x 0.30% = 29.997c -> 29c, never 30
        assertThat(ApplicationFeeCalculator.fee(usd(9999), 30, 0)).isEqualTo(usd(29));
    }

    @Test
    void fixedCentsAreAddedAfterTheFlooredSpread() {
        assertThat(ApplicationFeeCalculator.fee(usd(10_000), 25, 5)).isEqualTo(usd(30));
    }

    @Test
    void zeroSpreadAndZeroFixedIsZero() {
        assertThat(ApplicationFeeCalculator.fee(usd(5000), 0, 0)).isEqualTo(usd(0));
    }

    @Test
    void feeNeverExceedsTheCharge() {
        // 10c charge with a 30c fixed part: the platform can take at most the charge itself.
        assertThat(ApplicationFeeCalculator.fee(usd(10), 0, 30)).isEqualTo(usd(10));
    }

    @Test
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> ApplicationFeeCalculator.fee(usd(-1), 10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApplicationFeeCalculator.fee(usd(100), -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApplicationFeeCalculator.fee(usd(100), 10_001, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApplicationFeeCalculator.fee(usd(100), 10, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApplicationFeeCalculator.fee(usd(Long.MAX_VALUE), 10_000, 0)).isInstanceOf(ArithmeticException.class);
    }
}
