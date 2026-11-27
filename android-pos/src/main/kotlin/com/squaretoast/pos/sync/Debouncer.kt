package com.squaretoast.pos.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runs [action] once, [waitMillis] after the last [poke]: a burst of live events becomes one refresh. */
class Debouncer(
    private val scope: CoroutineScope,
    private val waitMillis: Long,
    private val action: suspend () -> Unit,
) {
    private var pending: Job? = null

    fun poke() {
        pending?.cancel()
        pending = scope.launch {
            delay(waitMillis)
            action()
        }
    }
}

object LiveRefreshRules {
    /** Re-read an open guest bill, but never while a charge is running: the charge reloads it when it ends. */
    fun reloadBill(guestCheck: String?, charging: Boolean): Boolean = guestCheck != null && !charging
}
