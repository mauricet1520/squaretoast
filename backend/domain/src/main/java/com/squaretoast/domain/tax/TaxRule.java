package com.squaretoast.domain.tax;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One jurisdiction's rate for a category; mirrors {@code tax_rule}. A null channel means any channel. Effective on
 * {@code [effectiveFrom, effectiveTo)}. Rates are parts per million (8.875% = 88 750).
 */
public record TaxRule(String stateCode, String jurisdictionCode, String taxCategory, @Nullable FulfillmentChannel channel,
                      int ratePpm, LocalDate effectiveFrom, @Nullable LocalDate effectiveTo) {

    private static final Pattern STATE = Pattern.compile("[A-Z]{2}");
    public static final int PPM = 1_000_000;

    public TaxRule {
        Objects.requireNonNull(jurisdictionCode, "jurisdictionCode");
        Objects.requireNonNull(taxCategory, "taxCategory");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (!STATE.matcher(stateCode).matches()) {
            throw new IllegalArgumentException("state code must be two uppercase letters: " + stateCode);
        }
        if (ratePpm < 0 || ratePpm > PPM) {
            throw new IllegalArgumentException("rate must be 0..1 000 000 ppm: " + ratePpm);
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
    }

    boolean effectiveOn(LocalDate date) {
        LocalDate to = effectiveTo;
        return !date.isBefore(effectiveFrom) && (to == null || date.isBefore(to));
    }
}
