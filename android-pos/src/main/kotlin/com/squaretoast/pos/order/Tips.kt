package com.squaretoast.pos.order

/** Tip suggestions, on the pre-tax subtotal only (never on tax or service charges), in whole cents. */
object Tips {
    val PERCENTS = listOf(15, 18, 20)

    /** [percent] of [subtotalCents], rounded half up. */
    fun percentOf(subtotalCents: Long, percent: Int): Long = TODO("Slice 10")

    /** What a card payment with this tip charges: the server's amount due plus the tip. */
    fun charge(dueCents: Long, tipCents: Long): Long = TODO("Slice 10")
}
