package com.squaretoast.domain.tax;

import com.squaretoast.domain.money.CurrencyCode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Everything about where and how a check is sold that tax depends on: the location's state and CA 80/80 inputs,
 * the state profile's service-charge rule, the order's channel and the business date.
 */
public record TaxContext(String stateCode, LocalDate date, FulfillmentChannel channel, boolean ca8080Applies,
                         boolean caSeparateAccountingElected, boolean mandatoryServiceChargeTaxable, CurrencyCode currency) {

    public TaxContext {
        Objects.requireNonNull(stateCode, "stateCode");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(currency, "currency");
    }
}
