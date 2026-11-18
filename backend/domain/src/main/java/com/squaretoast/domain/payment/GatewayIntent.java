package com.squaretoast.domain.payment;

import org.jspecify.annotations.Nullable;

/**
 * A processor-side payment intent. {@code clientSecret} lets the Terminal SDK or the hosted payment page confirm
 * it, so it is kept out of {@link #toString()} and logs.
 */
public record GatewayIntent(String intentRef, @Nullable String clientSecret) {

    @Override
    public String toString() {
        return "GatewayIntent[intentRef=" + intentRef + "]";
    }
}
