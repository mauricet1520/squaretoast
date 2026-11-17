package com.squaretoast.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @Test
    void storesIntegerMinorUnitsWithIsoCurrency() {
        Money m = Money.ofCents(1999, USD);
        assertThat(m.cents()).isEqualTo(1999L);
        assertThat(m.currency().code()).isEqualTo("USD");
    }

    @Test
    void addsAndSubtractsExactly() {
        Money a = Money.ofCents(1050, USD);
        Money b = Money.ofCents(275, USD);
        assertThat(a.plus(b)).isEqualTo(Money.ofCents(1325, USD));
        assertThat(b.minus(a)).isEqualTo(Money.ofCents(-775, USD));
    }

    @Test
    void multipliesByIntegerQuantity() {
        assertThat(Money.ofCents(1299, USD).times(3)).isEqualTo(Money.ofCents(3897, USD));
    }

    @Test
    void rejectsMixedCurrencies() {
        Money usd = Money.ofCents(100, USD);
        Money cad = Money.ofCents(100, CurrencyCode.of("CAD"));
        assertThatThrownBy(() -> usd.plus(cad)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> usd.minus(cad)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void neverSilentlyOverflows() {
        Money max = Money.ofCents(Long.MAX_VALUE, USD);
        assertThatThrownBy(() -> max.plus(Money.ofCents(1, USD))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> max.times(2)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void zeroAndSignHelpers() {
        assertThat(Money.zero(USD).cents()).isZero();
        assertThat(Money.ofCents(-1, USD).isNegative()).isTrue();
        assertThat(Money.ofCents(0, USD).isNegative()).isFalse();
    }

    @Test
    void currencyCodeMustBeThreeUppercaseLetters() {
        assertThatThrownBy(() -> CurrencyCode.of("usd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrencyCode.of("US")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrencyCode.of("XYZ")).isInstanceOf(IllegalArgumentException.class); // not ISO-4217
    }
}
