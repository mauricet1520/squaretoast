package com.squaretoast.order;

/** Size limits for one order from a POS (plan S7-6). Guest QR orders have tighter caps of their own. */
final class OrderLimits {

    static final int MAX_QTY = 999;
    static final int MAX_LINES = 200;
    static final int MAX_OPTIONS_PER_LINE = 20;

    private OrderLimits() {}
}
