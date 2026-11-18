package com.squaretoast.domain.payment;

import com.squaretoast.domain.money.Money;

/** Processor-neutral payments (plan §4). Card data never passes through here: the SDK or hosted page collects it. */
public interface PaymentGatewayPort {

    /** An intent the Terminal SDK (Tap to Pay or a paired reader) collects. */
    GatewayIntent createCardPresentIntent(ChargeRequest request);

    /** An intent a guest pays on the processor's hosted payment page (QR pay-at-table). */
    GatewayIntent createCardNotPresentIntent(ChargeRequest request);

    /** The processor's own fee on a settled charge, read from its balance transaction. */
    Money processorFee(String balanceTransactionRef);
}
