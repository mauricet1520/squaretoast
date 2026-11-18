package com.squaretoast.pos.payment

enum class PaymentMethod { TAP_TO_PAY, BLUETOOTH_READER, QR_PAY_AT_TABLE }

enum class ReaderModel { STRIPE_M2, WISEPAD_3 }

data class PairedReader(val serialNumber: String, val model: ReaderModel)

/** offline = the payment will run without the network (BT reader offline mode, or the guest's cellular for QR). */
data class PaymentChoice(val method: PaymentMethod, val offline: Boolean, val deniedBecause: DenyReason?)

/** Tap to Pay when the gate allows it, else a paired Bluetooth reader, else QR Pay-at-Table. */
class PaymentMethodSelector(private val gate: PaymentGate, private val pairedReader: () -> PairedReader?) {

    suspend fun select(): PaymentChoice {
        val result = gate.check()
        if (result is GateResult.Allowed) return PaymentChoice(PaymentMethod.TAP_TO_PAY, offline = false, deniedBecause = null)
        val reason = (result as GateResult.Denied).reason
        val offline = reason == DenyReason.OFFLINE
        val method = if (pairedReader() != null) PaymentMethod.BLUETOOTH_READER else PaymentMethod.QR_PAY_AT_TABLE
        return PaymentChoice(method, offline, reason)
    }
}
