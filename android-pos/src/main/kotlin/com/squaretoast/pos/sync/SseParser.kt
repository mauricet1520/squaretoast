package com.squaretoast.pos.sync

/** One server-sent event. Only the name and id matter to the POS; it re-reads state over REST. */
data class SseEvent(val id: Long?, val name: String, val data: String)

/** Slice 13: a minimal text/event-stream line parser (comments and `retry:` are ignored). */
class SseParser {
    private var id: Long? = null
    private var name: String? = null
    private val data = StringBuilder()

    /** Feed one line; returns an event when a blank line ends one. */
    fun line(line: String): SseEvent? {
        if (line.isEmpty()) {
            val event = if (name != null || data.isNotEmpty()) SseEvent(id, name ?: "message", data.toString()) else null
            id = null
            name = null
            data.setLength(0)
            return event
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "id" -> id = value.toLongOrNull()
            "event" -> name = value
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
        }
        return null
    }
}
