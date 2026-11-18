package com.squaretoast.domain.payment;

/** A processor call failed. Carries only the processor's error code, never request or card data. */
public final class GatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;

    public GatewayException(String code, boolean retryable) {
        super("payment processor error: " + code);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    /** True for network and 5xx failures, where retrying with the same idempotency key is safe. */
    public boolean retryable() {
        return retryable;
    }
}
