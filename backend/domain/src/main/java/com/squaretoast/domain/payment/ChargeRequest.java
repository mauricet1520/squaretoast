package com.squaretoast.domain.payment;

import com.squaretoast.domain.money.CurrencyMismatchException;
import com.squaretoast.domain.money.Money;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One destination charge: {@code amount} (incl. {@code tip}) settles to {@code connectedAccountRef}; the platform
 * keeps {@code applicationFee}. {@code idempotencyKey} is the payment's own key, reused on every retry.
 */
public record ChargeRequest(UUID idempotencyKey, UUID checkId, Money amount, Money tip, Money applicationFee,
                            String connectedAccountRef) {

    private static final Pattern ACCOUNT = Pattern.compile("acct_[A-Za-z0-9]+");

    public ChargeRequest {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(checkId, "checkId");
        sameCurrency(amount, tip);
        sameCurrency(amount, applicationFee);
        if (amount.cents() <= 0) {
            throw new IllegalArgumentException("charge amount must be positive: " + amount);
        }
        if (tip.isNegative() || tip.cents() > amount.cents()) {
            throw new IllegalArgumentException("tip must be 0..amount: " + tip);
        }
        if (applicationFee.isNegative() || applicationFee.cents() > amount.cents()) {
            throw new IllegalArgumentException("application fee must be 0..amount: " + applicationFee);
        }
        if (!ACCOUNT.matcher(connectedAccountRef).matches()) {
            throw new IllegalArgumentException("not a connected account id");
        }
    }

    private static void sameCurrency(Money a, Money b) {
        if (!a.currency().equals(b.currency())) {
            throw new CurrencyMismatchException(a.currency(), b.currency());
        }
    }
}
