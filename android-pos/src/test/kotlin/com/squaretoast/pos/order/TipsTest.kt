package com.squaretoast.pos.order

import com.squaretoast.pos.domain.SplitAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TipsTest {

    @Test
    fun suggestionsAreOnTheSubtotalRoundedHalfUp() {
        assertEquals(listOf(15, 18, 20), Tips.PERCENTS)
        assertEquals(600L, Tips.percentOf(4000, 15))
        assertEquals(720L, Tips.percentOf(4000, 18))
        assertEquals(195L, Tips.percentOf(1299, 15)) // 194.85
        assertEquals(234L, Tips.percentOf(1299, 18)) // 233.82
        assertEquals(290L, Tips.percentOf(1450, 20))
        assertEquals(1L, Tips.percentOf(5, 10))      // 0.5 rounds up
        assertEquals(0L, Tips.percentOf(4, 10))      // 0.4 rounds down
        assertEquals(0L, Tips.percentOf(0, 20))
    }

    @Test
    fun largeChecksDoNotOverflowOrLoseCents() {
        assertEquals(1_800_000_000L, Tips.percentOf(10_000_000_000L, 18))
    }

    @Test
    fun negativeInputsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { Tips.percentOf(-1, 15) }
        assertThrows(IllegalArgumentException::class.java) { Tips.percentOf(1000, -5) }
        assertThrows(IllegalArgumentException::class.java) { Tips.charge(4290, -1) }
    }

    @Test
    fun theChargeIsTheServersAmountDuePlusTheTip() {
        assertEquals(4290L + 720L, Tips.charge(4290, 720))
        assertEquals(4290L, Tips.charge(4290, 0))
    }

    @Test
    fun anEvenSplitOfTheRealAmountDueAddsUpExactly() {
        val shares = SplitAllocator.evenly(4290, 4)
        assertEquals(4290L, shares.sum())
        assertEquals(listOf(1073L, 1073L, 1072L, 1072L), shares)
    }
}
