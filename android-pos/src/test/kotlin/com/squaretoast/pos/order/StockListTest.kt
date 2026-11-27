package com.squaretoast.pos.order

import com.squaretoast.pos.sync.StockItem
import org.junit.Assert.assertEquals
import org.junit.Test

class StockListTest {

    private val bacon = StockItem("1", "Bacon", tracked = true, onHand = 4, eightySixed = false)
    private val patty = StockItem("2", "Burger patty", tracked = true, onHand = 0, eightySixed = false)
    private val lettuce = StockItem("3", "Lettuce", tracked = false, onHand = null, eightySixed = true)

    @Test
    fun soldOutComesFirstThenByName() {
        // Patty has 0 on hand, so it is sold out too, even though nobody 86'd it.
        assertEquals(listOf(patty, lettuce, bacon), StockList.show(listOf(bacon, lettuce, patty), ""))
    }

    @Test
    fun searchMatchesAnyPartOfTheNameIgnoringCase() {
        assertEquals(listOf(patty), StockList.show(listOf(bacon, lettuce, patty), "  PAT "))
        assertEquals(emptyList<StockItem>(), StockList.show(listOf(bacon), "fish"))
    }

    @Test
    fun soldOutMeansManually86dOrNoneLeft() {
        assertEquals(true, StockList.soldOut(lettuce))
        assertEquals(true, StockList.soldOut(patty))
        assertEquals(false, StockList.soldOut(bacon))
    }

    @Test
    fun theLabelSaysHowManyAreLeft() {
        assertEquals("4 left", StockList.label(bacon))
        assertEquals("None left", StockList.label(patty))
        assertEquals("86'd", StockList.label(lettuce))
        assertEquals("Not counted", StockList.label(lettuce.copy(eightySixed = false)))
    }
}
