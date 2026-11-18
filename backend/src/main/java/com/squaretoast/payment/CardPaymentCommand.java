package com.squaretoast.payment;

import java.util.Objects;
import java.util.UUID;

/** Start (or retry) a card payment on a check; the amount includes the tip, in the location's currency. */
public record CardPaymentCommand(UUID idempotencyKey, UUID checkId, CardMethod method, long amountCents, long tipCents) {

    public CardPaymentCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(checkId, "checkId");
        Objects.requireNonNull(method, "method");
    }
}
