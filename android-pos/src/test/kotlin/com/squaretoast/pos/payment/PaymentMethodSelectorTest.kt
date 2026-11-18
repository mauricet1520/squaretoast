package com.squaretoast.pos.payment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentMethodSelectorTest {

    private fun gate(result: GateResult) = object : PaymentGate {
        override suspend fun check() = result
    }

    private val m2 = PairedReader(serialNumber = "STRM26-0001", model = ReaderModel.STRIPE_M2)

    private fun select(result: GateResult, reader: PairedReader?) =
        runBlocking { PaymentMethodSelector(gate(result)) { reader }.select() }

    @Test
    fun prefersTapToPayWhenTheGateAllowsIt() {
        assertEquals(PaymentChoice(PaymentMethod.TAP_TO_PAY, offline = false, deniedBecause = null),
            select(GateResult.Allowed, m2))
    }

    @Test
    fun fallsBackToAPairedBluetoothReaderOnline() {
        assertEquals(
            PaymentChoice(PaymentMethod.BLUETOOTH_READER, offline = false, deniedBecause = DenyReason.API_BELOW_33),
            select(GateResult.Denied(DenyReason.API_BELOW_33), m2),
        )
    }

    @Test
    fun aBluetoothReaderIsFlaggedOfflineWhenTheNetworkIsDown() {
        val wisePad = PairedReader("WPC3-0002", ReaderModel.WISEPAD_3)
        assertEquals(
            PaymentChoice(PaymentMethod.BLUETOOTH_READER, offline = true, deniedBecause = DenyReason.OFFLINE),
            select(GateResult.Denied(DenyReason.OFFLINE), wisePad),
        )
    }

    @Test
    fun fallsBackToQrPayAtTableWithoutAReader() {
        assertEquals(
            PaymentChoice(PaymentMethod.QR_PAY_AT_TABLE, offline = true, deniedBecause = DenyReason.OFFLINE),
            select(GateResult.Denied(DenyReason.OFFLINE), null),
        )
        assertEquals(
            PaymentChoice(PaymentMethod.QR_PAY_AT_TABLE, offline = false, deniedBecause = DenyReason.NO_NFC),
            select(GateResult.Denied(DenyReason.NO_NFC), null),
        )
    }
}
