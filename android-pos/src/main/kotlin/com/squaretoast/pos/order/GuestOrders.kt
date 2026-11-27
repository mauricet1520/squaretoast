package com.squaretoast.pos.order

import com.squaretoast.pos.sync.GuestOrder
import com.squaretoast.pos.ui.money

/** How the POS shows guest QR orders: ones to accept, and one chip per unpaid check. */
object GuestOrders {

    data class Chip(val checkId: String, val label: String)

    fun waiting(orders: List<GuestOrder>): List<GuestOrder> = orders.filter { it.status == "WAITING" }

    /**
     * One chip per unpaid check, in the server's order (newest first). When a table has several open orders, each
     * gets a number by arrival ("T1 #1" is the oldest) so staff can tell them apart.
     */
    fun chips(orders: List<GuestOrder>): List<Chip> {
        val open = orders.filter { it.status == "ACCEPTED" }
            .map { o -> o to o.checks.filter { it.status == "OPEN" && it.dueCents > 0 } }
            .filter { (_, checks) -> checks.isNotEmpty() }
        val number = open.map { it.first }.groupBy { it.table }.filterValues { it.size > 1 }
            .values.flatMap { same -> same.sortedWith(compareBy({ arrival(it) }, { it.orderId })).mapIndexed { i, o -> o.orderId to i + 1 } }
            .toMap()
        return open.flatMap { (o, checks) ->
            val table = number[o.orderId]?.let { "${o.table} #$it" } ?: o.table
            checks.map { c ->
                val name = if (o.checks.size > 1) "$table ${c.label}" else table
                Chip(c.checkId, "$name · ${money(c.dueCents)}")
            }
        }
    }

    /** Parsed, not compared as text: "18:00:00.5Z" sorts before "18:00:00Z" as a string. */
    private fun arrival(o: GuestOrder): java.time.Instant =
        runCatching { java.time.Instant.parse(o.openedAt) }.getOrDefault(java.time.Instant.MAX)

    fun summary(order: GuestOrder): String = order.items.joinToString(", ")
}
