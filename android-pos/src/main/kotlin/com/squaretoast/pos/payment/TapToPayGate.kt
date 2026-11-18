package com.squaretoast.pos.payment

/** What the gate needs from the device, the backend and the Terminal SDK. */
interface DeviceSignals {
    val sdkInt: Int
    fun hasNfc(): Boolean
    fun nfcEnabled(): Boolean
    /** NET_CAPABILITY_VALIDATED on the active network, not just "connected". */
    fun networkValidated(): Boolean
    suspend fun connectionTokenRoundTrip(): Boolean
    /** Terminal.supportsReadersOfType(TAP_TO_PAY_DEVICE, …). */
    fun tapToPaySupported(): Boolean
    /** The SDK's connect step, which runs device attestation. */
    suspend fun attest(): Boolean
}

enum class DenyReason {
    API_BELOW_33, NO_NFC, NFC_DISABLED, OFFLINE, CONNECTION_TOKEN_FAILED, READER_UNSUPPORTED, ATTESTATION_FAILED
}

sealed interface GateResult {
    data object Allowed : GateResult
    data class Denied(val reason: DenyReason) : GateResult
}

fun interface PaymentGate {
    suspend fun check(): GateResult
}

/**
 * Tap to Pay has no offline mode and no chip/swipe fallback, so it is offered only when every check passes.
 * Cheap local checks run first; network round-trips run only once the device is known to be capable and online.
 */
class TapToPayGate(private val signals: DeviceSignals) : PaymentGate {

    override suspend fun check(): GateResult {
        val reason = when {
            signals.sdkInt < 33 -> DenyReason.API_BELOW_33
            !signals.hasNfc() -> DenyReason.NO_NFC
            !signals.nfcEnabled() -> DenyReason.NFC_DISABLED
            !signals.networkValidated() -> DenyReason.OFFLINE
            !signals.connectionTokenRoundTrip() -> DenyReason.CONNECTION_TOKEN_FAILED
            !signals.tapToPaySupported() -> DenyReason.READER_UNSUPPORTED
            !signals.attest() -> DenyReason.ATTESTATION_FAILED
            else -> return GateResult.Allowed
        }
        return GateResult.Denied(reason)
    }
}
