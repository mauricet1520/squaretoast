package com.squaretoast.domain.labor;

/** Deducting card fees from tips is not allowed in this state. */
public final class ProhibitedDeductionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProhibitedDeductionException(String stateCode) {
        super("card fees may not be deducted from tips in " + stateCode);
    }
}
