package com.squaretoast.pos.order

import com.squaretoast.pos.sync.StockItem

/** How the POS "Sold out" list filters, orders and labels stock. */
object StockList {

    /** Sold out: someone 86'd it, or it is counted and none are left. */
    fun soldOut(item: StockItem): Boolean = item.eightySixed || (item.tracked && (item.onHand ?: 0) <= 0)

    /** Items whose name contains [search] (trimmed, any case); sold-out items first, then by name. */
    fun show(items: List<StockItem>, search: String): List<StockItem> {
        val q = search.trim()
        return items.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .sortedWith(compareBy<StockItem> { !soldOut(it) }.thenBy { it.name.lowercase() }.thenBy { it.id })
    }

    fun label(item: StockItem): String = when {
        item.eightySixed -> "86'd"
        !item.tracked -> "Not counted"
        (item.onHand ?: 0) <= 0 -> "None left"
        else -> "${item.onHand} left"
    }
}
