package com.squaretoast.payment;

/** Card payment channels; names match payment.method. */
public enum CardMethod {
    /** Terminal SDK: Tap to Pay or a paired Bluetooth reader. */
    CARD_PRESENT,
    /** Guest pays on the hosted payment page from the table QR. */
    QR_CNP
}
