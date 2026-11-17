package com.squaretoast.domain.money;

/** Money as a signed {@code long} count of minor units (cents) in one ISO-4217 currency. Overflow throws. */
public record Money(long cents, CurrencyCode currency) {

    public static Money ofCents(long cents, CurrencyCode currency) {
        return new Money(cents, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(cents, other.cents), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(cents, other.cents), currency);
    }

    public Money times(int quantity) {
        return new Money(Math.multiplyExact(cents, quantity), currency);
    }

    public boolean isNegative() {
        return cents < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
