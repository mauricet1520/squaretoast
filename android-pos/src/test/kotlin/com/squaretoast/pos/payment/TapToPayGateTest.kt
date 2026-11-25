package com.squaretoast.pos.payment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TapToPayGateTest {

    /** Device and SDK answers as plain values; counts the two network round-trips. */
    private data class Signals(
        override val sdkInt: Int = 34,
        val nfcPresent: Boolean = true,
        val nfcOn: Boolean = true,
        val validated: Boolean = true,
        val tokenOk: Boolean = true,
        val supported: Boolean = true,
        val attested: Boolean = true,
    ) : DeviceSignals {
        var tokenCalls = 0
        var attestCalls = 0
        override fun hasNfc() = nfcPresent
        override fun nfcEnabled() = nfcOn
        override fun networkValidated() = validated
        override suspend fun connectionTokenRoundTrip(): Boolean { tokenCalls++; return tokenOk }
        override fun tapToPaySupported() = supported
        override suspend fun attest(): Boolean { attestCalls++; return attested }
    }

    private fun check(s: Signals) = runBlocking { TapToPayGate(s).check() }

    @Test
    fun allowsOnlyWhenEveryCheckPasses() {
        val s = Signals()
        assertEquals(GateResult.Allowed, check(s))
        assertEquals(1, s.tokenCalls)
        assertEquals(1, s.attestCalls)
    }

    @Test
    fun deniesBelowAndroid13() {
        assertEquals(GateResult.Denied(DenyReason.API_BELOW_33), check(Signals(sdkInt = 32)))
        assertEquals(GateResult.Allowed, check(Signals(sdkInt = 33)))
    }

    @Test
    fun deniesWithoutUsableNfc() {
        assertEquals(GateResult.Denied(DenyReason.NO_NFC), check(Signals(nfcPresent = false)))
        assertEquals(GateResult.Denied(DenyReason.NFC_DISABLED), check(Signals(nfcOn = false)))
    }

    @Test
    fun deniesOfflineWithoutTouchingTheNetwork() {
        val s = Signals(validated = false)
        assertEquals(GateResult.Denied(DenyReason.OFFLINE), check(s))
        assertEquals(0, s.tokenCalls)
        assertEquals(0, s.attestCalls)
    }

    @Test
    fun deniesWhenTheBackendCannotIssueAConnectionToken() {
        val s = Signals(tokenOk = false)
        assertEquals(GateResult.Denied(DenyReason.CONNECTION_TOKEN_FAILED), check(s))
        assertEquals(0, s.attestCalls)
    }

    @Test
    fun deniesUnsupportedDevicesBeforeAttesting() {
        val s = Signals(supported = false)
        assertEquals(GateResult.Denied(DenyReason.READER_UNSUPPORTED), check(s))
        assertEquals(0, s.attestCalls)
    }

    @Test
    fun deniesWhenAttestationFails() {
        assertEquals(GateResult.Denied(DenyReason.ATTESTATION_FAILED), check(Signals(attested = false)))
    }
}
