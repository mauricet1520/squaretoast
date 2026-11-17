package com.squaretoast.domain.money;

import java.util.Currency;
import java.util.regex.Pattern;

/** ISO-4217 alphabetic currency code. */
public record CurrencyCode(String code) {

    private static final Pattern ALPHA3 = Pattern.compile("[A-Z]{3}");

    public CurrencyCode {
        if (!ALPHA3.matcher(code).matches()) {
            throw new IllegalArgumentException("currency must be 3 uppercase letters: " + code);
        }
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not an ISO-4217 currency: " + code, e);
        }
    }

    public static CurrencyCode of(String code) {
        return new CurrencyCode(code);
    }
}
