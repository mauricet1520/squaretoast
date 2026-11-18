package com.squaretoast.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stripe's publishable key, which guest pages need to load the Payment Element. It is public by design; the
 * secret key never leaves the server.
 */
@Component
public class PublishableKey {

    private final String key;

    PublishableKey(@Value("${squaretoast.stripe.publishable-key:}") String key) {
        this.key = key;
    }

    /** Throws {@link PaymentException.NotConfigured} when no {@code pk_} key is set. */
    public String require() {
        if (!key.startsWith("pk_")) {
            throw new PaymentException.NotConfigured("no Stripe publishable key");
        }
        return key;
    }
}
