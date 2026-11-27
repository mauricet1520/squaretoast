package com.squaretoast.pos.order

import com.squaretoast.pos.sync.GuestOrder
import com.squaretoast.pos.ui.money

/** How the POS shows guest QR orders: ones to accept, and one chip per unpaid check. */
object GuestOrders {

    data class Chip(val checkId: String, val label: String)

    fun waiting(orders: List<GuestOrder>): List<GuestOrder> = orders.filter { it.status == "WAITING" }

    fun chips(orders: List<GuestOrder>): List<Chip> = orders.filter { it.status == "ACCEPTED" }.flatMap { o ->
        val open = o.checks.filter { it.status == "OPEN" && it.dueCents > 0 }
        open.map { c ->
            val name = if (o.checks.size > 1) "${o.table} ${c.label}" else o.table
            Chip(c.checkId, "$name · ${money(c.dueCents)}")
        }
    }

    fun summary(order: GuestOrder): String = order.items.joinToString(", ")
}
