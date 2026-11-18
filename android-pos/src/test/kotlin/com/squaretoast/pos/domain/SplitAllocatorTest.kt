package com.squaretoast.pos.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SplitAllocatorTest {

    private val cases = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("split-allocation.json")).readText(),
    ).jsonObject["cases"]!!.jsonArray

    @Test
    fun followsSharedVectors() {
        for (c in cases) {
            val o = c.jsonObject
            val amount = o["amount"]!!.jsonPrimitive.long
            val weights = o["weights"]!!.jsonArray.map { it.jsonPrimitive.long }
            val expected = o["expected"]!!
            if (expected is JsonNull) {
                assertThrows("$amount by $weights", IllegalArgumentException::class.java) {
                    SplitAllocator.allocate(amount, weights)
                }
            } else {
                assertEquals("$amount by $weights", expected.jsonArray.map { it.jsonPrimitive.long },
                    SplitAllocator.allocate(amount, weights))
            }
        }
    }

    @Test
    fun evenSplitIsEqualWeights() {
        assertEquals(listOf(334L, 333L, 333L), SplitAllocator.evenly(1000, 3))
        assertThrows(IllegalArgumentException::class.java) { SplitAllocator.evenly(1000, 0) }
    }

    @Test
    fun sharesAlwaysSumToTheAmountWithoutOverflow() {
        val amount = 9_999_999_999L
        val shares = SplitAllocator.allocate(amount, listOf(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, 7))
        assertEquals(listOf(5_000_000_000L, 4_999_999_999L, 0L), shares)
    }
}
