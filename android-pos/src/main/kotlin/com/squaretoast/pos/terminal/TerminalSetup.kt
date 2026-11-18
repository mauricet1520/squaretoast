package com.squaretoast.pos.terminal

import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider
import com.stripe.stripeterminal.external.callable.TapToPayReaderListener
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.TapUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Stripe Terminal 6.0.0 configs. Card data stays inside the SDK; nothing here sees PAN, CVV or PIN. */
object TerminalConfigs {
    /** Simulated readers only in debuggable builds, so a release build can never take a fake payment. */
    fun tapToPayDiscovery(debuggable: Boolean) = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(isSimulated = debuggable)

    /**
     * The location is a platform-account Terminal Location created for this merchant location. Routing to the
     * connected account (on_behalf_of, transfer_data) is set server-side on the PaymentIntent, not here.
     */
    fun tapToPayConnection(locationId: String, listener: TapToPayReaderListener) =
        ConnectionConfiguration.TapToPayConnectionConfiguration(
            useCase = TapUseCase.Pay(locationId),
            autoReconnectOnUnexpectedDisconnect = true,
            tapToPayReaderListener = listener,
        )
}

/**
 * Fetches a fresh connection token from our backend each time the SDK asks (tokens must not be cached).
 * Failures reach the SDK with a fixed message so backend errors, which may carry credentials, are never surfaced.
 */
class TerminalTokenProvider(
    dispatcher: CoroutineDispatcher,
    private val fetch: suspend () -> String,
) : ConnectionTokenProvider {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
        scope.launch {
            val token = try {
                fetch()
            } catch (_: Exception) {
                null
            }
            if (token != null) callback.onSuccess(token) else callback.onFailure(ConnectionTokenException(FAILURE))
        }
    }

    private companion object { const val FAILURE = "connection token unavailable" }
}
