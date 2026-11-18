package com.squaretoast.payment;

import java.util.Objects;
import java.util.UUID;

/**
 * Start (or retry) a card payment; the amount includes the tip, in the location's currency. {@code checkId} is a
 * server check, or for an offline claim ({@code offlineClaim}) the POS's own check id that the claim names.
 */
public record CardPaymentCommand(UUID idempotencyKey, UUID checkId, CardMethod method, long amountCents, long tipCents,
                                 boolean offlineClaim) {

    public CardPaymentCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(checkId, "checkId");
        Objects.requireNonNull(method, "method");
    }

    public CardPaymentCommand(UUID idempotencyKey, UUID checkId, CardMethod method, long amountCents, long tipCents) {
        this(idempotencyKey, checkId, method, amountCents, tipCents, false);
    }
}
