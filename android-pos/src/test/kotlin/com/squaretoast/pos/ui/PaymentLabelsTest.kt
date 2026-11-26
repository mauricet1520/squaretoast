package com.squaretoast.pos.ui

import com.squaretoast.pos.payment.DenyReason
import com.squaretoast.pos.payment.PaymentChoice
import com.squaretoast.pos.payment.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Staff see plain words, never enum names, for the chosen payment method and why Tap to Pay was skipped. */
class PaymentLabelsTest {

    @Test
    fun methodsHavePlainNames() {
        assertEquals("Tap to Pay", PaymentLabels.method(PaymentChoice(PaymentMethod.TAP_TO_PAY, false, null)))
        assertEquals("Card reader", PaymentLabels.method(PaymentChoice(PaymentMethod.BLUETOOTH_READER, false, null)))
        assertEquals("QR pay at table", PaymentLabels.method(PaymentChoice(PaymentMethod.QR_PAY_AT_TABLE, false, null)))
        assertEquals("QR pay at table (offline)", PaymentLabels.method(PaymentChoice(PaymentMethod.QR_PAY_AT_TABLE, true, null)))
    }

    @Test
    fun everyDenyReasonHasAPlainSentence() {
        for (r in DenyReason.entries) {
            val text = PaymentLabels.reason(PaymentChoice(PaymentMethod.QR_PAY_AT_TABLE, false, r))!!
            assert(text.isNotBlank() && !text.contains('_')) { "$r -> $text" }
        }
        assertEquals("Tap to Pay unavailable: can't reach the payment service",
            PaymentLabels.reason(PaymentChoice(PaymentMethod.QR_PAY_AT_TABLE, false, DenyReason.CONNECTION_TOKEN_FAILED)))
    }

    @Test
    fun noReasonWhenTapToPayWasNotDenied() {
        assertNull(PaymentLabels.reason(PaymentChoice(PaymentMethod.TAP_TO_PAY, false, null)))
    }
}
