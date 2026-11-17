package com.squaretoast.domain.money;

import java.util.Currency;
import java.util.regex.Pattern;

/** ISO-4217 alphabetic currency code whose minor unit is cents (exactly 2 fraction digits). */
public record CurrencyCode(String code) {

    private static final Pattern ALPHA3 = Pattern.compile("[A-Z]{3}");
    private static final int CENTS_DIGITS = 2;

    public CurrencyCode {
        if (!ALPHA3.matcher(code).matches()) {
            throw new IllegalArgumentException("currency must be 3 uppercase letters: " + code);
        }
        Currency iso;
        try {
            iso = Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not an ISO-4217 currency: " + code, e);
        }
        if (iso.getDefaultFractionDigits() != CENTS_DIGITS) {
            throw new IllegalArgumentException(
                    code + " has " + iso.getDefaultFractionDigits() + " minor digits; Money models cents (2)");
        }
    }

    public static CurrencyCode of(String code) {
        return new CurrencyCode(code);
    }
}
