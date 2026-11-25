package com.squaretoast.pos.terminal

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.OfflineListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.callable.TapToPayReaderListener
import com.stripe.stripeterminal.external.callable.TerminalListener
import com.stripe.stripeterminal.external.models.CollectPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.ConfirmPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.DeviceType
import com.stripe.stripeterminal.external.models.LocaleConfig
import com.stripe.stripeterminal.external.models.OfflineStatus
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.ReaderSupportResult
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.log.LogLevel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Thin wrapper over the Stripe Terminal SDK. The SDK owns card reading, PIN entry and the payment sheet;
 * this class only passes a client secret in and gets a PaymentIntent status out. SDK logging is off.
 */
class TerminalSession(
    private val app: Application,
    private val tokens: TerminalTokenProvider,
    private val debuggable: Boolean,
) {
    private val offline = object : OfflineListener {
        override fun onOfflineStatusChange(offlineStatus: OfflineStatus) = Unit
        override fun onPaymentIntentForwarded(paymentIntent: PaymentIntent, e: TerminalException?) = Unit
        override fun onForwardingFailure(e: TerminalException) = Unit
    }

    fun ensureInitialized() {
        if (!Terminal.isInitialized()) {
            Terminal.init(app, LogLevel.NONE, tokens, object : TerminalListener {}, offline,
                LocaleConfig.CardLanguagePreferenceIfAvailable)
        }
    }

    fun tapToPaySupported(): Boolean = Terminal.getInstance()
        .supportsReadersOfType(DeviceType.TAP_TO_PAY_DEVICE, TerminalConfigs.tapToPayDiscovery(debuggable)) is
        ReaderSupportResult.Supported

    /** Discovers this phone as a reader and connects; the SDK runs device attestation during connect. */
    suspend fun connectTapToPay(locationId: String): Boolean {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val reader = suspendCancellableCoroutine<Reader?> { cont ->
            var cancelable: com.stripe.stripeterminal.external.callable.Cancelable? = null
            cancelable = Terminal.getInstance().discoverReaders(
                TerminalConfigs.tapToPayDiscovery(debuggable),
                object : DiscoveryListener {
                    override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                        val first = readers.firstOrNull() ?: return
                        if (cont.isActive) cont.resume(first)
                        cancelable?.cancel(NoOp)
                    }
                },
                object : Callback {
                    override fun onSuccess() { if (cont.isActive) cont.resume(null) }
                    override fun onFailure(e: TerminalException) { if (cont.isActive) cont.resume(null) }
                },
            )
            cont.invokeOnCancellation { cancelable.cancel(NoOp) }
        } ?: return false
        return suspendCancellableCoroutine { cont ->
            Terminal.getInstance().connectReader(reader,
                TerminalConfigs.tapToPayConnection(locationId, object : TapToPayReaderListener {}),
                object : ReaderCallback {
                    override fun onSuccess(reader: Reader) = cont.resume(true)
                    override fun onFailure(e: TerminalException) = cont.resume(false)
                })
        }
    }

    /** Collects and confirms a PaymentIntent the backend created (amount, tip and routing are server-side). */
    suspend fun collect(clientSecret: String): PaymentIntent? {
        val intent = suspendCancellableCoroutine<PaymentIntent?> { cont ->
            Terminal.getInstance().retrievePaymentIntent(clientSecret, intentCallback(cont))
        } ?: return null
        return suspendCancellableCoroutine { cont ->
            Terminal.getInstance().processPaymentIntent(intent,
                CollectPaymentIntentConfiguration.Builder().build(),
                ConfirmPaymentIntentConfiguration.Builder().build(),
                intentCallback(cont))
        }
    }

    private fun intentCallback(cont: kotlinx.coroutines.CancellableContinuation<PaymentIntent?>) =
        object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) { if (cont.isActive) cont.resume(paymentIntent) }
            override fun onFailure(e: TerminalException) { if (cont.isActive) cont.resume(null) }
        }

    private object NoOp : Callback {
        override fun onSuccess() = Unit
        override fun onFailure(e: TerminalException) = Unit
    }
}
