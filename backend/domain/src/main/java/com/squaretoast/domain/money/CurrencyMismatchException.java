package com.squaretoast.domain.money;

public final class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(CurrencyCode left, CurrencyCode right) {
        super("currency mismatch: " + left.code() + " vs " + right.code());
    }
}
