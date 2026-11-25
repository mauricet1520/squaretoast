package com.squaretoast.pos.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this POS holds a device token the cloud still accepts. When the cloud rejects the token (the device was
 * revoked), it is forgotten and the UI asks for enrollment again. The outbox is untouched, so unsynced orders
 * send once the device is enrolled again.
 */
class DeviceSession(private val token: () -> String?, private val clear: () -> Unit) {

    private val state = MutableStateFlow(token() != null)
    val enrolled: StateFlow<Boolean> = state.asStateFlow()

    fun token(): String? = token.invoke()

    /** A new token was stored. */
    @Synchronized
    fun saved() {
        state.value = token() != null
    }

    /** The cloud answered 401 to the stored token. */
    @Synchronized
    fun rejected() {
        clear()
        state.value = false
    }

    /** The cloud answered 401 to [sent]; forget it only if it is still the stored token, not a newer enrollment's. */
    @Synchronized
    fun rejected(sent: String) {
        if (token() == sent) rejected()
    }
}
