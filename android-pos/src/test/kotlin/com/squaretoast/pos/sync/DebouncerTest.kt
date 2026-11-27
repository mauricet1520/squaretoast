package com.squaretoast.pos.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {

    @Test
    fun aBurstOfEventsBecomesOneRefresh() = runTest {
        var runs = 0
        val d = Debouncer(backgroundScope, 300) { runs++ }
        d.poke(); advanceTimeBy(100)
        d.poke(); advanceTimeBy(100)
        d.poke()
        advanceTimeBy(299); runCurrent()
        assertEquals(0, runs)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun eventsFarApartEachRefresh() = runTest {
        var runs = 0
        val d = Debouncer(backgroundScope, 300) { runs++ }
        d.poke(); advanceTimeBy(301); runCurrent()
        d.poke(); advanceTimeBy(301); runCurrent()
        assertEquals(2, runs)
    }

    @Test
    fun nothingRunsWithoutAPoke() = runTest {
        var runs = 0
        Debouncer(backgroundScope, 300) { runs++ }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, runs)
    }

    @Test
    fun theBillReloadsOnlyWhenAGuestCheckIsOpenAndNoChargeIsRunning() {
        assertEquals(true, LiveRefreshRules.reloadBill(guestCheck = "c1", charging = false))
        assertEquals(false, LiveRefreshRules.reloadBill(guestCheck = "c1", charging = true))
        assertEquals(false, LiveRefreshRules.reloadBill(guestCheck = null, charging = false))
    }
}
