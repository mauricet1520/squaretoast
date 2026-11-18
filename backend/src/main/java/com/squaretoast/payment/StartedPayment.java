package com.squaretoast.payment;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The payment row and the processor intent the device or hosted page confirms. */
public record StartedPayment(UUID paymentId, String intentRef, @Nullable String clientSecret) {

    @Override
    public String toString() {
        return "StartedPayment[paymentId=" + paymentId + ", intentRef=" + intentRef + "]";
    }
}
