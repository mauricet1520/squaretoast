package com.squaretoast.payment.adapter.stripe;

/** The webhook did not come from Stripe (or is too old to trust). Deliberately says nothing more. */
public final class InvalidWebhookSignatureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidWebhookSignatureException() {
        super("invalid Stripe-Signature");
    }
}
