package com.squaretoast.pos.ui

import com.squaretoast.pos.payment.DenyReason
import com.squaretoast.pos.payment.PaymentChoice
import com.squaretoast.pos.payment.PaymentMethod

/** Plain words for staff; enum names never reach the screen. */
object PaymentLabels {

    fun method(choice: PaymentChoice): String {
        val name = when (choice.method) {
            PaymentMethod.TAP_TO_PAY -> "Tap to Pay"
            PaymentMethod.BLUETOOTH_READER -> "Card reader"
            PaymentMethod.QR_PAY_AT_TABLE -> "QR pay at table"
        }
        return if (choice.offline) "$name (offline)" else name
    }

    fun reason(choice: PaymentChoice): String? = choice.deniedBecause?.let {
        "Tap to Pay unavailable: " + when (it) {
            DenyReason.API_BELOW_33 -> "needs Android 13 or newer"
            DenyReason.NO_NFC -> "this phone has no NFC"
            DenyReason.NFC_DISABLED -> "turn on NFC in Settings"
            DenyReason.OFFLINE -> "no internet connection"
            DenyReason.CONNECTION_TOKEN_FAILED -> "can't reach the payment service"
            DenyReason.READER_UNSUPPORTED -> "this phone isn't supported"
            DenyReason.ATTESTATION_FAILED -> "this phone failed the security check"
        }
    }
}
