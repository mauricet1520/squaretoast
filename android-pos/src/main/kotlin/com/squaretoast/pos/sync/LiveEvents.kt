package com.squaretoast.pos.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Slice 13: listens to the location's live stream and calls back when guest orders or checks may have changed:
 * once per (re)connect ([CONNECTED]) and on each `order`, `course` or `check` event. A dropped stream reconnects
 * with a fresh ticket and the last event id, so nothing is missed. It stops when the cloud rejects the device.
 */
class LiveEvents(private val api: CloudApi, private val sleep: suspend (Long) -> Unit = { delay(it) }) {

    suspend fun run(onChange: suspend (String) -> Unit) {
        var lastId: Long? = null
        var attempt = 0
        while (true) {
            try {
                val ticket = api.streamTicket()
                api.liveStream(ticket, lastId, onOpen = { attempt = 0; onChange(CONNECTED) }) { e ->
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
            sleep(backoffMillis(attempt++))
        }
    }

    companion object {
        const val CONNECTED = "connected"
        val TOPICS = setOf("order", "course", "check")

        /** 1 s, 2 s, 4 s … capped at 30 s. */
        fun backoffMillis(attempt: Int): Long = min(30_000L, 1_000L shl min(attempt, 5))
    }
}
