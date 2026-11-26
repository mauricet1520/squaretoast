package com.squaretoast.pos.order

/** Tip suggestions, on the pre-tax subtotal only (never on tax or service charges), in whole cents. */
object Tips {
    val PERCENTS = listOf(15, 18, 20)

    /** [percent] of [subtotalCents], rounded half up. */
    fun percentOf(subtotalCents: Long, percent: Int): Long {
        require(subtotalCents >= 0 && percent >= 0) { "no negative tips" }
        // Exact long math (API 24+); overflow throws instead of wrapping.
        return Math.addExact(Math.multiplyExact(subtotalCents, percent.toLong()), 50L) / 100L
    }

    /** What a card payment with this tip charges: the server's amount due plus the tip. */
    fun charge(dueCents: Long, tipCents: Long): Long {
        require(dueCents >= 0 && tipCents >= 0) { "no negative amounts" }
        return Math.addExact(dueCents, tipCents)
    }
}
