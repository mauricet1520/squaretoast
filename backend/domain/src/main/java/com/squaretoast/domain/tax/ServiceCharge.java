package com.squaretoast.domain.tax;

import com.squaretoast.domain.money.Money;
import java.util.Objects;

/**
 * A charge on the check that is not a voluntary tip. Mandatory charges and auto-gratuities are taxed per the
 * state profile; a delivery fee carries its own taxability. Voluntary tips are never a ServiceCharge.
 */
public record ServiceCharge(Kind kind, Money amount, boolean deliveryFeeTaxable) {

    public enum Kind { MANDATORY, AUTO_GRATUITY, DELIVERY_FEE }

    public ServiceCharge {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("service charge cannot be negative: " + amount);
        }
        if (deliveryFeeTaxable && kind != Kind.DELIVERY_FEE) {
            throw new IllegalArgumentException("only a delivery fee sets its own taxability");
        }
    }

    public static ServiceCharge mandatory(Money amount) {
        return new ServiceCharge(Kind.MANDATORY, amount, false);
    }

    public static ServiceCharge autoGratuity(Money amount) {
        return new ServiceCharge(Kind.AUTO_GRATUITY, amount, false);
    }

    public static ServiceCharge deliveryFee(Money amount, boolean taxable) {
        return new ServiceCharge(Kind.DELIVERY_FEE, amount, taxable);
    }
}
