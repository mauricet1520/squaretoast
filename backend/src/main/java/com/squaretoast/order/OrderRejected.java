package com.squaretoast.order;

/** A POS mutation the server will not apply. The reason is a stable code the device shows and keeps. */
public final class OrderRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public OrderRejected(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
