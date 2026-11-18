package com.squaretoast.pos.terminal

import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.TapToPayReaderListener
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import com.stripe.stripeterminal.external.models.TapUseCase
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TerminalSetupTest {

    @Test
    fun releaseBuildsNeverDiscoverSimulatedReaders() {
        assertFalse(TerminalConfigs.tapToPayDiscovery(debuggable = false).isSimulated)
        assertTrue(TerminalConfigs.tapToPayDiscovery(debuggable = true).isSimulated)
    }

    @Test
    fun connectsForPaymentsAtTheMerchantsTerminalLocation() {
        val listener = object : TapToPayReaderListener {}
        val config = TerminalConfigs.tapToPayConnection("tml_FAKE123", listener)

        assertEquals("tml_FAKE123", (config.useCase as TapUseCase.Pay).locationId)
        assertTrue(config.autoReconnectOnUnexpectedDisconnect)
        assertEquals(listener, config.tapToPayReaderListener)
    }

    private class Captured : ConnectionTokenCallback {
        val done = CountDownLatch(1)
        var token: String? = null
        var error: ConnectionTokenException? = null
        override fun onSuccess(token: String) { this.token = token; done.countDown() }
        override fun onFailure(e: ConnectionTokenException) { error = e; done.countDown() }
    }

    @Test
    fun handsTheBackendIssuedTokenToTheSdk() {
        val cb = Captured()
        TerminalTokenProvider(Dispatchers.IO) { "pst_test_secret" }.fetchConnectionToken(cb)
        assertTrue(cb.done.await(5, TimeUnit.SECONDS))
        assertEquals("pst_test_secret", cb.token)
        assertNull(cb.error)
    }

    @Test
    fun reportsBackendFailuresWithoutLeakingTheCause() {
        val cb = Captured()
        TerminalTokenProvider(Dispatchers.IO) { throw IOException("Authorization: Bearer sk_live_leak") }
            .fetchConnectionToken(cb)
        assertTrue(cb.done.await(5, TimeUnit.SECONDS))
        assertNull(cb.token)
        assertEquals("connection token unavailable", cb.error!!.message)
        assertFalse(cb.error.toString().contains("sk_live"))
    }
}
