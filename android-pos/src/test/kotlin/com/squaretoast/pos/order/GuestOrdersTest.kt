package com.squaretoast.pos.order

import com.squaretoast.pos.sync.GuestCheck
import com.squaretoast.pos.sync.GuestOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestOrdersTest {

    private fun check(id: String, due: Long, status: String = "OPEN") = GuestCheck(id, "Check 1", status, due, "USD")

    private val waiting = GuestOrder("o1", "T7", "2026-11-26T18:05:00Z", "WAITING", listOf("2× Burger (Cheddar)"), emptyList())
    private val accepted = GuestOrder("o2", "T3", "2026-11-26T18:00:00Z", "ACCEPTED", listOf("1× Fries"), listOf(check("c2", 1823)))
    private val paid = GuestOrder("o3", "T4", "2026-11-26T17:00:00Z", "ACCEPTED", listOf("1× Fries"), listOf(check("c3", 0, "PAID")))

    @Test
    fun waitingOrdersAreTheOnesStaffMustAccept() {
        assertEquals(listOf(waiting), GuestOrders.waiting(listOf(accepted, waiting, paid)))
    }

    @Test
    fun eachUnpaidCheckBecomesAChipWithTableAndAmountDue() {
        assertEquals(listOf(GuestOrders.Chip("c2", "T3 · $18.23")), GuestOrders.chips(listOf(waiting, accepted, paid)))
    }

    @Test
    fun anOrderWithSeveralChecksNamesEachCheck() {
        val split = accepted.copy(checks = listOf(check("a", 1000).copy(label = "Seat 1"), check("b", 500).copy(label = "Seat 2")))
        assertEquals(
            listOf(GuestOrders.Chip("a", "T3 Seat 1 · $10.00"), GuestOrders.Chip("b", "T3 Seat 2 · $5.00")),
            GuestOrders.chips(listOf(split)),
        )
    }

    @Test
    fun theWaitingSummaryJoinsTheItems() {
        assertEquals("2× Burger (Cheddar), 1× Fries", GuestOrders.summary(waiting.copy(items = waiting.items + "1× Fries")))
    }

    @Test
    fun twoOrdersFromTheSameTableAreNumberedOldestFirst() {
        val later = accepted.copy(orderId = "o9", openedAt = "2026-11-26T18:30:00Z", checks = listOf(check("c9", 500)))
        val other = accepted.copy(orderId = "o8", table = "T5", checks = listOf(check("c8", 700)))
        assertEquals(
            listOf(GuestOrders.Chip("c9", "T3 #2 · $5.00"), GuestOrders.Chip("c8", "T5 · $7.00"), GuestOrders.Chip("c2", "T3 #1 · $18.23")),
            GuestOrders.chips(listOf(later, other, accepted)), // the server lists newest first
        )
    }

    @Test
    fun aPaidEarlierOrderDoesNotNumberTheOpenOne() {
        val earlierPaid = paid.copy(table = "T3")
        assertEquals(listOf(GuestOrders.Chip("c2", "T3 · $18.23")), GuestOrders.chips(listOf(accepted, earlierPaid)))
    }

    @Test
    fun arrivalIsComparedAsATimeNotAsText() {
        val a = accepted.copy(orderId = "a", openedAt = "2026-11-26T18:00:00Z", checks = listOf(check("ca", 100)))
        val b = accepted.copy(orderId = "b", openedAt = "2026-11-26T18:00:00.500Z", checks = listOf(check("cb", 200)))
        assertEquals(listOf("T3 #2 · $2.00", "T3 #1 · $1.00"), GuestOrders.chips(listOf(b, a)).map { it.label })
    }
}
