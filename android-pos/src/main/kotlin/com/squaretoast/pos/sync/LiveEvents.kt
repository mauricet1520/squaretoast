package com.squaretoast.pos.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Slice 13: listens to the location's live stream and calls back when guest orders or checks may have changed:
 * once per (re)connect ([CONNECTED]) and on each `order`, `course` or `check` event. A dropped stream reconnects
 * with a fresh ticket and the last event id, so nothing is missed. It stops when the cloud rejects the device.
 */
class LiveEvents(
    private val api: CloudApi,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun run(onChange: suspend (String) -> Unit) {
        var lastId: Long? = null
        var attempt = 0
        while (true) {
            var openedAt: Long? = null
            var healthy = false // delivered an event, or stayed open a while: start the back-off over
            try {
                val ticket = api.streamTicket()
                api.liveStream(ticket, lastId, onOpen = { openedAt = clock(); onChange(CONNECTED) }) { e ->
                    healthy = true
                    e.id?.let { lastId = it }
                    if (e.name in TOPICS) onChange(e.name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RejectedBatchException) {
                if (e.status == 401 || e.status == 403) return // revoked or re-enrolled: nothing to listen for
            } catch (e: Exception) {
                // offline, timed out or the server restarted: back off and try again
            }
            val opened = openedAt
            if (healthy || (opened != null && clock() - opened >= HEALTHY_MILLIS)) attempt = 0
            sleep(backoffMillis(attempt++))
        }
    }

    companion object {
        const val CONNECTED = "connected"
        val TOPICS = setOf("order", "course", "check")
        const val HEALTHY_MILLIS = 10_000L

        /** 1 s, 2 s, 4 s … capped at 30 s. */
        fun backoffMillis(attempt: Int): Long = min(30_000L, 1_000L shl min(attempt, 5))
    }
}
