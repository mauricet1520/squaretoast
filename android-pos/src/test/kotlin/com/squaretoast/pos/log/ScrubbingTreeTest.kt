package com.squaretoast.pos.log

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubbingTreeTest {

    private val written = mutableListOf<Pair<String?, String>>()
    private val tree = ScrubbingTree { _, tag, message -> written += tag to message }

    @Test
    fun redactsCardNumbersThatPassLuhn() {
        tree.log(Log.INFO, "Pay", "card 4242 4242 4242 4242 approved", null)
        tree.log(Log.INFO, "Pay", "pan=4000056655665556;", null)
        tree.log(Log.INFO, "Pay", "amex 3782-822463-10005", null)
        assertEquals(
            listOf("card [REDACTED] approved", "pan=[REDACTED];", "amex [REDACTED]"),
            written.map { it.second },
        )
    }

    @Test
    fun keepsLongNumbersThatAreNotCardNumbers() {
        tree.log(Log.INFO, "Sync", "seq 1234567890123 ok", null) // fails Luhn
        tree.log(Log.INFO, "Sync", "total 5009 cents", null)
        assertEquals(listOf("seq 1234567890123 ok", "total 5009 cents"), written.map { it.second })
    }

    @Test
    fun dropsEverythingTaggedByTheStripeSdk() {
        tree.log(Log.DEBUG, "StripeTerminal", "reader payload", null)
        tree.log(Log.DEBUG, "com.stripe.stripeterminal.Foo", "payload", null)
        assertTrue(written.isEmpty())
    }

    @Test
    fun scrubsStackTracesToo() {
        tree.log(Log.ERROR, "Pay", "failed", IllegalStateException("bad card 4242424242424242"))
        val line = written.single().second
        assertTrue(line.startsWith("failed"))
        assertTrue(line.contains("[REDACTED]"))
        assertTrue(!line.contains("4242424242424242"))
    }
}
