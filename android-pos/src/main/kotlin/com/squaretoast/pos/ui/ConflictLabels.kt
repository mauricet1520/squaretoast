package com.squaretoast.pos.ui

import com.squaretoast.pos.data.OutboxEntity

/** Plain words for a refused change; server reason codes never reach the screen. */
object ConflictLabels {
    fun describe(row: OutboxEntity, tableLabel: String?): String {
        val what = when (row.type) {
            "ORDER_PLACED" -> if (tableLabel != null) "The order for $tableLabel" else "An order"
            "COURSE_TRANSITIONED" -> "A course change"
            else -> "A change"
        }
        val why = when (row.lastError) {
            "OVERSOLD" -> "an item sold out before it was sent"
            "INVALID_MODIFIERS" -> "an item's options are missing or not allowed"
            "INVALID_LINE" -> "an item or option isn't allowed"
            "ITEM_NOT_AT_LOCATION" -> "an item isn't on this restaurant's menu"
            "ITEM_INACTIVE" -> "an item was taken off the menu"
            "STALE_STATE" -> "the course had already moved on another screen"
            else -> "the server refused it"
        }
        return "$what wasn't accepted: $why."
    }
}
