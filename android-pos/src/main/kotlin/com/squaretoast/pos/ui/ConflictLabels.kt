package com.squaretoast.pos.ui

import com.squaretoast.pos.data.OutboxEntity

/** Plain words for a refused change; server reason codes never reach the screen. */
object ConflictLabels {
    fun describe(row: OutboxEntity, tableLabel: String?): String = TODO("conflict OK")
}
