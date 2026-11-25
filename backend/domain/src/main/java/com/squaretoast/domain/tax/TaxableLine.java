package com.squaretoast.domain.tax;

import com.squaretoast.domain.money.Money;
import java.util.Objects;

/** A line's share of one check (price incl. modifiers x qty, after split allocation). */
public record TaxableLine(String taxCategory, FoodTemp temp, Money amount) {

    public TaxableLine {
        Objects.requireNonNull(taxCategory, "taxCategory");
        Objects.requireNonNull(temp, "temp");
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("line amount cannot be negative: " + amount);
        }
    }
}
