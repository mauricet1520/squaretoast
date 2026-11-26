package com.squaretoast.pos.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CartTest {

    private val burger = MenuChoice("burger", "Burger", 1299, available = true, needsOptions = false)
    private val fries = MenuChoice("fries", "Fries", 450, available = true, needsOptions = false)
    private val soldOut = MenuChoice("pie", "Pie", 800, available = false, needsOptions = false)
    private val steak = MenuChoice("steak", "Steak", 3150, available = true, needsOptions = true)
    private val location = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    @Test
    fun theSameItemSeatAndCourseMergesIntoOneLine() {
        val cart = Cart().add(burger, seat = 1, course = 1).add(burger, seat = 1, course = 1)
        assertEquals(1, cart.lines.size)
        assertEquals(2, cart.lines.single().quantity)
    }

    @Test
    fun anotherSeatOrCourseIsItsOwnLine() {
        val cart = Cart().add(burger, 1, 1).add(burger, 2, 1).add(burger, 1, 2)
        assertEquals(3, cart.lines.size)
        assertTrue(cart.lines.all { it.quantity == 1 })
    }

    @Test
    fun quantityZeroRemovesTheLine() {
        val cart = Cart().add(burger, 1, 1).add(fries, 1, 1).setQuantity("burger", 1, 1, 0)
        assertEquals(listOf("fries"), cart.lines.map { it.menuItemId })
        assertThrows(IllegalArgumentException::class.java) { cart.setQuantity("fries", 1, 1, -1) }
    }

    @Test
    fun soldOutAndNeedsOptionsItemsCannotBeAdded() {
        assertFalse(Cart().canAdd(soldOut))
        assertFalse(Cart().canAdd(steak))
        assertTrue(Cart().canAdd(burger))
        assertThrows(IllegalArgumentException::class.java) { Cart().add(soldOut, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { Cart().add(steak, 1, 1) }
    }

    @Test
    fun seatsAndCoursesStartAtOne() {
        assertThrows(IllegalArgumentException::class.java) { Cart().add(burger, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { Cart().add(burger, 1, 0) }
    }

    @Test
    fun subtotalIsExactWholeCents() {
        val cart = Cart().add(burger, 1, 1).add(burger, 1, 1).add(burger, 2, 1).add(fries, 1, 1)
        assertEquals(3 * 1299L + 450L, cart.subtotalCents)
        assertEquals(0L, Cart().subtotalCents)
    }

    @Test
    fun theOrderKeepsSeatCourseQuantityAndTheCartsIds() {
        val cart = Cart().add(burger, 2, 1).add(burger, 2, 1).add(fries, 1, 2)

        val first = cart.toPlaceOrder("  T4 ", location)
        val again = cart.toPlaceOrder("T4", location)

        assertEquals("T4", first.tableLabel)
        assertEquals(location, first.locationId)
        assertEquals(cart.mutationId, first.mutationId)
        assertEquals(cart.orderId, first.orderId)
        assertEquals(first.mutationId, again.mutationId)
        assertEquals(first.orderId, again.orderId)
        val b = first.lines.single { it.menuItemId == "burger" }
        assertEquals(listOf<Any>(2, 1, 2, 1299L), listOf<Any>(b.seat, b.course, b.quantity, b.unitPriceCents))
        val f = first.lines.single { it.menuItemId == "fries" }
        assertEquals(listOf(1, 2, 1), listOf(f.seat, f.course, f.quantity))
    }

    @Test
    fun anEmptyCartOrNoTableCannotBecomeAnOrder() {
        assertThrows(IllegalArgumentException::class.java) { Cart().toPlaceOrder("T1", location) }
        assertThrows(IllegalArgumentException::class.java) { Cart().add(burger, 1, 1).toPlaceOrder("  ", location) }
    }

    @Test
    fun changingTheCartKeepsItsIds() {
        val cart = Cart()
        val changed = cart.add(burger, 1, 1).add(fries, 1, 1).setQuantity("fries", 1, 1, 3)
        assertEquals(cart.mutationId, changed.mutationId)
        assertEquals(cart.orderId, changed.orderId)
    }
}
