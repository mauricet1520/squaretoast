package com.squaretoast.payment;

/** A correctly signed body that is not a Stripe event (no id or type). */
public final class MalformedWebhookException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MalformedWebhookException() {
        super("not a Stripe event");
    }
}
